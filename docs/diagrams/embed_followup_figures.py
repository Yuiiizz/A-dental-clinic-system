"""Stage updated follow-up diagrams in the course DOCX for visual review.

Run followup_figures.py first. This script does not overwrite the source Word
document; pass its path and a separate output DOCX path.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile
import sys

from docx import Document
from docx.oxml import OxmlElement
from docx.text.paragraph import Paragraph
from docx.shared import Pt


def caption(doc, number):
    hits = [p for p in doc.paragraphs if p.text.strip().startswith((f"图{number}", f"图 {number}"))]
    if len(hits) != 1:
        raise ValueError(f"Expected exactly one figure {number} caption, got {len(hits)}")
    return hits[0]


def media_part(doc, number):
    label = caption(doc, number)
    previous = label._p.getprevious()
    if previous is None:
        raise ValueError(f"Figure {number} has no preceding image paragraph")
    embeds = previous.xpath('.//a:blip/@r:embed')
    if len(embeds) != 1:
        raise ValueError(f"Figure {number} has {len(embeds)} embedded images")
    return str(doc.part.related_parts[embeds[0]].partname).lstrip("/")


def main(source, output):
    doc = Document(source)
    image50 = media_part(doc, 50)
    image70 = media_part(doc, 70)
    if image50 == image70:
        raise ValueError("Figure images unexpectedly share one media part")
    figure64 = caption(doc, 64)
    figure70 = caption(doc, 70)
    if len(figure70.runs) != 1:
        raise ValueError("Figure 70 caption formatting changed; update manually")
    figure70.runs[0].text = "图70  诊疗服务与复诊局部 ER 图"
    note_text = ("总览图只列主要外键；V021 新增的 followup_plan.service_item_id "
                 "可空引用 service_item.item_id，基数及字段见图70与 6.3.37 数据字典。")
    next_p = figure64._p.getnext()
    if next_p is not None and note_text not in Paragraph(next_p, figure64._parent).text:
        element = OxmlElement('w:p')
        figure64._p.addnext(element)
        note = Paragraph(element, figure64._parent)
        note.add_run(note_text)
        note.paragraph_format.space_before = Pt(3)
        note.paragraph_format.space_after = Pt(0)
        for run in note.runs:
            run.font.size = Pt(8)
    with TemporaryDirectory() as temp:
        interim = Path(temp) / "text-updated.docx"
        doc.save(interim)
        replacements = {
            image50: (Path(__file__).parent / "followup-class-relations.png").read_bytes(),
            image70: (Path(__file__).parent / "followup-local-er.png").read_bytes(),
        }
        output.parent.mkdir(parents=True, exist_ok=True)
        with ZipFile(interim) as source_zip, ZipFile(output, "w") as dest_zip:
            for info in source_zip.infolist():
                dest_zip.writestr(info, replacements.get(info.filename, source_zip.read(info.filename)))
    check = Document(output)
    if not any(row.cells[0].text == "service_item_id" for table in check.tables for row in table.rows):
        raise ValueError("Follow-up plan service item row disappeared")
    if note_text not in "\n".join(p.text for p in check.paragraphs):
        raise ValueError("ER overview note disappeared")
    print(output, image50, image70)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: embed_followup_figures.py source.docx output.docx")
    main(Path(sys.argv[1]), Path(sys.argv[2]))
