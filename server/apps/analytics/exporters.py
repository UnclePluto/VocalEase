from __future__ import annotations

import csv
from io import BytesIO, StringIO
import re
from typing import Iterable, Mapping

from openpyxl import Workbook


EXPORT_COLUMNS = (
    ("medical_record_no", "病历号"),
    ("name", "患者姓名"),
    ("treatment_status", "治疗状态"),
    ("primary_doctor", "主治医生"),
    ("completed_count", "完成演唱次数"),
    ("total_duration_seconds", "累计时长（秒）"),
    ("average_score", "平均得分"),
    ("score_trend", "得分趋势"),
    ("burp_improvement", "嗳气改善率"),
    ("is_mock", "模拟数据"),
)
MAX_CELL_LENGTH = 32767
_ILLEGAL_CONTROLS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def escape_sheet_cell(value):
    if value is None:
        return ""
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return value
    text = _ILLEGAL_CONTROLS.sub("�", str(value))[:MAX_CELL_LENGTH]
    effective = text.lstrip()
    if effective.startswith(("=", "+", "-", "@")):
        text = "'" + text
        text = text[:MAX_CELL_LENGTH]
    return text


class CsvExporter:
    mime = "text/csv"
    extension = "csv"

    def render(self, rows: Iterable[Mapping[str, object]]) -> bytes:
        stream = StringIO(newline="")
        writer = csv.writer(stream, lineterminator="\r\n")
        writer.writerow([label for _, label in EXPORT_COLUMNS])
        for row in rows:
            writer.writerow([escape_sheet_cell(row.get(key)) for key, _ in EXPORT_COLUMNS])
        return b"\xef\xbb\xbf" + stream.getvalue().encode("utf-8")


class XlsxExporter:
    mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    extension = "xlsx"

    def render(self, rows: Iterable[Mapping[str, object]]) -> bytes:
        workbook = Workbook(write_only=True)
        worksheet = workbook.create_sheet("患者统计")
        worksheet.append([label for _, label in EXPORT_COLUMNS])
        for row in rows:
            worksheet.append([escape_sheet_cell(row.get(key)) for key, _ in EXPORT_COLUMNS])
        stream = BytesIO()
        workbook.save(stream)
        return stream.getvalue()


def export_rows(rows: Iterable[Mapping[str, object]], export_format: str) -> bytes:
    exporter = CsvExporter() if export_format == "csv" else XlsxExporter() if export_format == "xlsx" else None
    if exporter is None:
        raise ValueError("不支持的导出格式")
    return exporter.render(rows)
