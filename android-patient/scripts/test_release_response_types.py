#!/usr/bin/env python3
"""验证发布响应检查器支持全限定名，且不会跨越接口边界。"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

class ReleaseResponseTypesTest(unittest.TestCase):
    def run_checker(self, broken=False):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            script = root / 'scripts/check_release_response_types.py'
            script.parent.mkdir()
            shutil.copyfile(Path(__file__).with_name('check_release_response_types.py'), script)
            source = root / 'app/src/main/java/com/vocaease/patient/core/network/VocaEaseApi.kt'
            source.parent.mkdir(parents=True)
            source.write_text('''interface VocaEaseApi {
 suspend fun sessionSongPlayback(id: String): ApiEnvelope<com.vocaease.patient.core.network.dto.SongPlaybackGrantDto>
 suspend fun referencePitch(id: String): ApiEnvelope<com.vocaease.patient.core.network.dto.ReferencePitchDto>
 suspend fun sessions(page: Int?): ApiEnvelope<SessionPageDto>
}''')
            mapping = root / 'mapping.txt'
            mapping.write_text('''com.vocaease.patient.core.network.VocaEaseApi -> a:
    java.lang.Object sessionSongPlayback(java.lang.String,kotlin.coroutines.Continuation) -> x
    java.lang.Object referencePitch(java.lang.String,kotlin.coroutines.Continuation) -> y
    java.lang.Object sessions(java.lang.Integer,kotlin.coroutines.Continuation) -> z
com.vocaease.patient.core.network.ApiEnvelope -> b:
com.vocaease.patient.core.network.dto.SongPlaybackGrantDto -> c:
com.vocaease.patient.core.network.dto.ReferencePitchDto -> d:
com.vocaease.patient.core.network.dto.SessionPageDto -> e:
''')
            analyzer = root / 'sdk/cmdline-tools/latest/bin/apkanalyzer'
            analyzer.parent.mkdir(parents=True)
            dex = '\n'.join(
                '.method public abstract ' + method + '()\n'
                ' .annotation system Ldalvik/annotation/Signature;\n'
                ' value = {"Lb<L' + dto + ';>;"}\n .end annotation\n.end method'
                for method, dto in [('x', 'java/lang/Object' if broken else 'c'), ('y', 'd'), ('z', 'e')]
            )
            analyzer.write_text('#!/usr/bin/env python3\nprint(' + repr(dex) + ')\n')
            analyzer.chmod(0o755)
            return subprocess.run(['python3', str(script), str(root/'release.apk'), str(mapping)],
                env={**os.environ, 'ANDROID_HOME':str(root/'sdk')}, capture_output=True, text=True)

    def test_qualified_responses_are_checked_independently(self):
        result = self.run_checker()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('3 个保留的接口', result.stdout)

    def test_object_substitution_is_rejected(self):
        result = self.run_checker(broken=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('sessionSongPlayback', result.stderr)
        self.assertIn('SongPlaybackGrantDto', result.stderr)

if __name__ == '__main__':
    unittest.main()
