"""Rebuild the two follow-up diagrams embedded in the course Word document.

The layout uses explicit lanes so relationship labels never sit on connectors.
Run with Python, matplotlib, and Windows Microsoft YaHei installed. Both PNGs
retain the aspect ratios of the corresponding Word images.
"""
from pathlib import Path
from matplotlib import pyplot as plt
from matplotlib.patches import Rectangle
from matplotlib.font_manager import FontProperties

OUT = Path(__file__).resolve().parent
FONT = FontProperties(fname="C:/Windows/Fonts/msyh.ttc")
INK = "#203e3b"
MUTED = "#496660"
LINE = "#8aa39d"
HEADER = "#e8f3f0"


def canvas(width, height):
    fig, ax = plt.subplots(figsize=(width / 100, height / 100), dpi=200)
    fig.subplots_adjust(0, 0, 1, 1)
    ax.set_xlim(0, width)
    ax.set_ylim(height, 0)
    ax.axis("off")
    return fig, ax


def txt(ax, x, y, value, size=20, ha="left", color=INK, weight="normal"):
    ax.text(x, y, value, fontproperties=FONT, fontsize=size, ha=ha, va="center",
            color=color, fontweight=weight)


def box(ax, x, y, w, h, title, lines, *, title_size=23, line_size=19, step=32):
    ax.add_patch(Rectangle((x, y), w, h, facecolor="white", edgecolor=LINE, linewidth=1.5))
    ax.add_patch(Rectangle((x, y), w, 52, facecolor=HEADER, edgecolor="none"))
    ax.plot([x, x + w], [y + 52, y + 52], color=LINE, lw=1.1)
    txt(ax, x + w / 2, y + 25, title, title_size, ha="center", weight="bold")
    for i, line in enumerate(lines):
        txt(ax, x + 18, y + 83 + i * step, line, line_size)


def line(ax, points):
    ax.plot([p[0] for p in points], [p[1] for p in points], color=LINE, lw=2,
            solid_capstyle="round", solid_joinstyle="round")


def label(ax, x, y, value, size=17):
    txt(ax, x, y, value, size, ha="center", color=MUTED)


def figure50():
    fig, ax = canvas(740, 1351)
    txt(ax, 370, 48, "复诊与联系 · 主要关系", 27, ha="center", weight="bold")
    box(ax, 210, 110, 320, 142, "就诊记录", ["过敏核对、临床记录"], step=34)
    box(ax, 210, 360, 320, 235, "复诊计划", ["建议日期范围", "预约时长与复诊目的", "计划状态、可选项目"], step=39)
    box(ax, 210, 700, 320, 142, "服务项目", ["当前可预约项目"], step=34)
    box(ax, 20, 955, 300, 185, "预约", ["初诊／复诊、时段", "预约状态、协调状态"], step=39)
    box(ax, 420, 955, 300, 185, "联系记录", ["联系渠道、答复与时间", "可关联计划或预约"], step=39)

    line(ax, [(370, 252), (370, 360)])
    label(ax, 306, 301, "来源就诊")
    label(ax, 390, 272, "1", 18)
    label(ax, 400, 341, "0..*", 18)

    line(ax, [(370, 595), (370, 700)])
    label(ax, 285, 647, "可预约项目")
    label(ax, 397, 614, "0..*", 18)
    label(ax, 400, 681, "0..1", 18)

    line(ax, [(210, 547), (168, 547), (168, 955)])
    label(ax, 92, 755, "落实计划")
    label(ax, 173, 523, "0..1", 18)
    label(ax, 174, 934, "0..*", 18)

    line(ax, [(530, 547), (620, 547), (620, 955)])
    label(ax, 644, 755, "联系跟进")
    label(ax, 602, 523, "0..1", 18)
    label(ax, 610, 934, "0..*", 18)

    ax.add_patch(Rectangle((20, 1198), 700, 105, facecolor="#f6faf9", edgecolor=LINE, linewidth=1))
    txt(ax, 36, 1230, "一份计划可关联多次历史预约，但至多一笔活动预约。", 18)
    txt(ax, 36, 1270, "联系结束不代表复诊目的完成；普通新预约不自动办结计划。", 18)
    fig.savefig(OUT / "followup-class-relations.png", dpi=200, facecolor="white")
    plt.close(fig)


def figure70():
    fig, ax = canvas(910, 1221)
    txt(ax, 455, 50, "诊疗服务与复诊 · 局部 ER", 26, ha="center", weight="bold")
    box(ax, 30, 115, 390, 445, "诊疗项目记录", [
        "care_item", "PK care_id", "FK visit_id → visit_record",
        "FK item_id → service_item", "FK informed_by → staff",
        "FK performed_by? → staff", "FK supersedes_id? → care_item",
    ], title_size=20, line_size=14, step=43)
    box(ax, 500, 115, 380, 445, "复诊计划", [
        "followup_plan", "PK plan_id", "FK source_visit_id → visit_record",
        "FK dentist_id → staff", "FK service_item_id? → service_item",
        "FK supersedes_id? → followup_plan",
    ], title_size=20, line_size=14, step=45)
    box(ax, 30, 695, 390, 385, "联系与提醒记录", [
        "contact_record", "PK contact_id", "FK plan_id? → followup_plan",
        "FK appointment_id? → appointment", "FK staff_id? → staff",
        "FK retry_of_id? → contact_record",
    ], title_size=20, line_size=13, step=43)
    box(ax, 500, 705, 380, 255, "服务项目", [
        "service_item", "PK item_id", "名称、启用状态、可预约标志",
    ], title_size=20, line_size=15, step=43)

    line(ax, [(690, 560), (690, 705)])
    label(ax, 588, 633, "可预约项目", 17)
    label(ax, 724, 584, "0..*", 18)
    label(ax, 728, 684, "0..1", 18)

    line(ax, [(500, 505), (460, 505), (460, 890), (420, 890)])
    label(ax, 457, 653, "跟进", 17)
    label(ax, 458, 484, "0..1", 13)
    label(ax, 455, 872, "0..*", 13)

    ax.add_patch(Rectangle((30, 1110), 850, 78, facecolor="#f6faf9", edgecolor=LINE, linewidth=1))
    txt(ax, 48, 1138, "PK 为主键，FK 为外键，? 表示可空；跨模块引用按字段标注。", 16)
    txt(ax, 48, 1166, "一份计划最多指定一个服务项目，同一项目可供多份计划引用。", 17)
    fig.savefig(OUT / "followup-local-er.png", dpi=200, facecolor="white")
    plt.close(fig)


if __name__ == "__main__":
    figure50()
    figure70()
