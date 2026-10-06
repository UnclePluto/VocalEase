#!/usr/bin/env python3
"""检查真实 Release DEX 中 Retrofit 响应泛型，防止 DTO 被压缩为 Object。"""
import os
from pathlib import Path
import re
import subprocess
import sys

if len(sys.argv) != 3:
    sys.exit("用法：check_release_response_types.py <release APK> <mapping.txt>")
apk, mapping_path = map(Path, sys.argv[1:])
root = Path(__file__).resolve().parents[1]
mapping = mapping_path.read_text()
classes = dict(re.findall(r"^([^\s]+) -> ([^:]+):$", mapping, re.M))
api = "com.vocaease.patient.core.network.VocaEaseApi"
envelope = "com.vocaease.patient.core.network.ApiEnvelope"
analyzer = Path(os.environ["ANDROID_HOME"]) / "cmdline-tools/latest/bin/apkanalyzer"
dex = subprocess.check_output([str(analyzer), "dex", "code", "--class", classes[api], str(apk)], text=True)
methods = re.findall(r"\.method[\s\S]*?\.end method", dex)
source = (root / "app/src/main/java/com/vocaease/patient/core/network/VocaEaseApi.kt").read_text()
responses = re.findall(r"suspend fun (\w+)\((?:(?!suspend fun)[\s\S])*?\):\s*ApiEnvelope<([\w.]+)>", source)
if not responses:
    sys.exit("未找到待检查的接口")
section = mapping.split(api + " -> " + classes[api] + ":", 1)[1].split("\ncom.", 1)[0]
errors = []
checked = 0
for method_name, dto in responses:
    renamed = re.search(r"java.lang.Object " + method_name + r"\([^\n]* -> (\w+)", section)
    if not renamed:
        if method_name == "changePassword":
            errors.append("changePassword: 改密接口已移除")
        continue  # 没有生产调用的接口方法可以被正常移除。
    checked += 1
    candidates = [v for k, v in classes.items() if k == dto or ("." not in dto and k.endswith("." + dto))]
    if len(candidates) != 1:
        errors.append(f"{method_name}: 响应类型 {dto} 已移除或无法定位")
        continue
    method = next((m for m in methods if re.search(r"\.method[^\n]* " + renamed[1] + r"\(", m)), "")
    signature = re.search(r"Ldalvik/annotation/Signature;[\s\S]*?\.end annotation", method)
    joined = "".join(re.findall(r'"([^"\n]*)"', signature[0])) if signature else ""
    expected = "L" + classes[envelope].replace(".", "/") + "<L" + candidates[0].replace(".", "/") + ";>;"
    if expected not in joined:
        errors.append(f"{method_name}: 响应泛型未保留 {dto}")
if errors:
    sys.exit("Release 响应类型检查失败：\n" + "\n".join(errors))
print(f"Release 响应类型检查通过：{checked} 个保留的接口")
