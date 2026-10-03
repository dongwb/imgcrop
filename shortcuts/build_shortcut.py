#!/usr/bin/env python3
"""生成「电影裁剪」iOS 快捷指令(plist → 签名)。

用法: python3 build_shortcut.py
产物: 电影裁剪.shortcut(正式版,已签名)、电影裁剪-测试.shortcut(固定参数,用于 CLI 实测)

序列化规则的权威依据见 memory: shortcut-serialization-rules
- 条件动作: WFCondition 为整数(0=< 4== 100=有任何值), WFNumberValue 为字符串,
  WFInput 需 {"Type":"Variable","Variable":{...}} 嵌套包装
- 动作输出引用: ActionOutput + OutputUUID + OutputName 三件套
- 省略 WFInput = 空输入(无隐式上一步传递)
- 数字入变量必须经「数字」动作中转
- 统计动作在循环内不工作 → 循环内取 min/max 用 If 比较
"""
import plistlib
import subprocess
import sys

def att(typ, **kw):
    return {"WFSerializationType": "WFTextTokenAttachment", "Value": {"Type": typ, **kw}}

def var(name):
    return att("Variable", VariableName=name)

def out(uuid, name):
    return att("ActionOutput", OutputUUID=uuid, OutputName=name)

def cond_input(inner):
    """条件动作 WFInput 的嵌套包装"""
    return {"Type": "Variable", "Variable": inner}

def text(s):
    """文本内容必须是 token 包装格式(裸字符串会得到空文本)"""
    return {"WFSerializationType": "WFTextTokenString",
            "Value": {"string": s, "attachmentsByRange": {}}}

SHORTCUT_INPUT = att("ExtensionInput")
REPEAT_ITEM = var("Repeat Item")

def act(ident, **params):
    return {"WFWorkflowActionIdentifier": ident, "WFWorkflowActionParameters": params}

IF = "is.workflow.actions.conditional"
MENU = "is.workflow.actions.choosefrommenu"
REPEAT = "is.workflow.actions.repeat.each"
SETV = "is.workflow.actions.setvariable"
MATH = "is.workflow.actions.math"
NUMBER = "is.workflow.actions.number"

G_IF, G_REP = ("11111111-1111-4111-8111-111111111111",
               "33333333-3333-4333-8333-333333333333")
G_MENU1, G_MENU2, G_MENU3 = ("22222222-2222-4222-8222-22222222222a",
                             "22222222-2222-4222-8222-22222222222b",
                             "22222222-2222-4222-8222-22222222222c")
G_ORIENT = "66666666-6666-4666-8666-666666666666"
G_IFW, G_IFH = ("44444444-4444-4444-8444-444444444444",
                "55555555-5555-4555-8555-555555555555")
G_POS, G_POS2 = ("88888888-8888-4888-8888-888888888888",
                  "88888888-8888-4888-8888-888888888887")
G_BARS1, G_BARS2, G_BARSON = ("77777777-7777-4777-8777-77777777777a",
                              "77777777-7777-4777-8777-77777777777b",
                              "77777777-7777-4777-8777-77777777777c")

# 黑边遮幅比例(与网页版 BAR_PCT_DEFAULT 一致:长边的 4.5385%)
BAR_RATIO = 0.045385
def make_black_b64(pixels=8192):
    """生成大尺寸纯黑 PNG 的 base64(构建期一次)。resize 原子已证实不可用,
    改为内嵌大黑图 → 用已验证的「裁剪」裁到画布尺寸。"""
    import base64, io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (pixels, pixels), (0, 0, 0)).save(buf, "PNG", optimize=True)
    return base64.b64encode(buf.getvalue()).decode()

BLACK_PNG_B64 = make_black_b64()

U = {k: f"0A0000{i:02X}-0000-4000-8000-0000000000{i:02X}" for i, k in enumerate([
    "sel", "c", "l1", "s1", "g", "w", "h", "o", "n1", "inv", "m1", "m2",
    "d1", "d2", "dx", "dy", "px", "py", "cr", "cr2", "cr3", "dl", "bar", "bar2",
    "b2n", "b2m", "pw", "ph", "rw", "rh", "blk", "blkd", "rs", "ov"], start=1)}
U_NUM = {i: f"0B0000{i:02X}-0000-4000-8000-0000000000{i:02X}" for i in range(1, 14)}

RATIOS = [("电影画幅 2.71:1", 2.71), ("宽银幕 2.17:1", 4096 / 1885), ("16:9", 1.7778),
          ("4:3", 1.3333), ("1:1 正方形", 1.0), ("3:4", 0.75), ("9:16 IG快拍", 0.5625)]
POSITIONS = [("居中", 0.5), ("靠上(竖版为靠左)", 0.0), ("靠下(竖版为靠右)", 1.0)]


def set_num(varname, value, uuid):
    """数字入变量必须经「数字」动作中转(直接塞 real 会得到空值)"""
    return [act(NUMBER, WFNumberActionNumber=value, UUID=uuid),
            act(SETV, WFVariableName=varname, WFInput=out(uuid, "Number"))]


def cond(condition, gid, true_actions, false_actions, input_att, number_value=None,
         end_uuid=None):
    """现代条件格式: 整数条件 + 字符串数值 + 嵌套输入;end_uuid 供 If Result 引用"""
    p = dict(WFControlFlowMode=0, GroupingIdentifier=gid,
             WFCondition=condition, WFInput=cond_input(input_att))
    if number_value is not None:
        p["WFNumberValue"] = number_value
    end = act(IF, WFControlFlowMode=2, GroupingIdentifier=gid)
    if end_uuid:
        end["WFWorkflowActionParameters"]["UUID"] = end_uuid
    return ([act(IF, **p)]
            + true_actions
            + [act(IF, WFControlFlowMode=1, GroupingIdentifier=gid)]
            + false_actions
            + [end])


def if_less_zero(input_att, gid, true_actions, false_actions):
    return cond(0, gid, true_actions, false_actions, input_att, number_value="0")


def math_to(op, a, b, result, uuid):
    """result = a op b"""
    return [act(MATH, WFMathOperation=op, WFInput=var(a), WFMathOperand=var(b), UUID=uuid),
            act(SETV, WFVariableName=result, WFInput=out(uuid, "Calculation Result"))]


def minvar(result, a, b, gid, duuid):
    """result = min(a, b) —— 统计动作在循环内不工作,用 If 比较差值"""
    return ([act(MATH, WFMathOperation="-", WFInput=var(a),
                 WFMathOperand=var(b), UUID=duuid)]
            + if_less_zero(out(duuid, "Calculation Result"), gid,
                           [act(SETV, WFVariableName=result, WFInput=var(a))],
                           [act(SETV, WFVariableName=result, WFInput=var(b))]))


def crop_loop(save, bars):
    """逐张:横竖自适应比例 + 位置偏移 + 可选黑边遮幅"""
    body = [
        act(REPEAT, WFControlFlowMode=0, GroupingIdentifier=G_REP, WFInput=var("批量")),
        act("is.workflow.actions.properties.images", WFContentItemPropertyName="Width",
            WFInput=REPEAT_ITEM, UUID=U["w"]),
        act(SETV, WFVariableName="图宽", WFInput=out(U["w"], "Width")),
        act("is.workflow.actions.properties.images", WFContentItemPropertyName="Height",
            WFInput=REPEAT_ITEM, UUID=U["h"]),
        act(SETV, WFVariableName="图高", WFInput=out(U["h"], "Height")),
        # ── 横竖判断:宽-高<0 即竖版 → 实际比例 = 1/比例 ──
        act(MATH, WFMathOperation="-", WFInput=var("图宽"),
            WFMathOperand=var("图高"), UUID=U["o"]),
    ]
    body += if_less_zero(
        out(U["o"], "Calculation Result"), G_ORIENT,
        true_actions=[  # 竖版
            act(NUMBER, WFNumberActionNumber=1, UUID=U["n1"]),
            act(MATH, WFMathOperation="÷", WFInput=out(U["n1"], "Number"),
                WFMathOperand=var("比例"), UUID=U["inv"]),
            act(SETV, WFVariableName="实际比例", WFInput=out(U["inv"], "Calculation Result")),
        ],
        false_actions=[  # 横版/方形
            act(SETV, WFVariableName="实际比例", WFInput=var("比例")),
        ])
    body += math_to("×", "图高", "实际比例", "目标宽", U["m1"])
    body += math_to("÷", "图宽", "实际比例", "目标高", U["m2"])
    body += minvar("裁剪宽", "图宽", "目标宽", G_IFW, U["d1"])
    body += minvar("裁剪高", "图高", "目标高", G_IFH, U["d2"])
    # ── 位置:三档,全部用已验证的「小于」条件(cond 4「等于」未验证,曾致全灭) ──
    body += math_to("-", "图宽", "裁剪宽", "差X", U["dx"])
    body += math_to("-", "图高", "裁剪高", "差Y", U["dy"])
    body += math_to("×", "差X", "位置系数", "X", U["px"])
    body += math_to("×", "差Y", "位置系数", "Y", U["py"])
    crop_center = [act("is.workflow.actions.image.crop", WFInput=REPEAT_ITEM,
                       WFImageCropPosition="Center",
                       WFImageCropWidth=var("裁剪宽"), WFImageCropHeight=var("裁剪高"),
                       UUID=U["cr"]),
                   act(SETV, WFVariableName="裁剪结果",
                       WFInput=out(U["cr"], "Cropped Image"))]
    crop_custom = [act("is.workflow.actions.image.crop", WFInput=REPEAT_ITEM,
                       WFImageCropPosition="Custom",
                       WFImageCropX=var("X"), WFImageCropY=var("Y"),
                       WFImageCropWidth=var("裁剪宽"), WFImageCropHeight=var("裁剪高"),
                       UUID=U["cr2"]),
                   act(SETV, WFVariableName="裁剪结果",
                       WFInput=out(U["cr2"], "Cropped Image"))]
    crop_bottom = [act("is.workflow.actions.image.crop", WFInput=REPEAT_ITEM,
                       WFImageCropPosition="Custom",
                       WFImageCropX=var("X"), WFImageCropY=var("Y"),
                       WFImageCropWidth=var("裁剪宽"), WFImageCropHeight=var("裁剪高"),
                       UUID=U["cr3"]),
                   act(SETV, WFVariableName="裁剪结果",
                       WFInput=out(U["cr3"], "Cropped Image"))]
    # 位置系数 <0.25 → 靠上/左;<0.75(嵌套) → 居中;否则 → 靠下/右
    body += cond(0, G_POS,
                 crop_custom,
                 cond(0, G_POS2, crop_center, crop_bottom,
                      var("位置系数"), number_value="0.75"),
                 var("位置系数"), number_value="0.25")
    # ── 黑边遮幅(可选):长边的 4.5385%,横版加上下、竖版加左右 ──
    bars_branch = [
        act(MATH, WFMathOperation="-", WFInput=var("裁剪宽"),
            WFMathOperand=var("裁剪高"), UUID=U["dl"]),
    ]
    # 长边 = max(裁剪宽, 裁剪高)
    bars_branch += if_less_zero(
        out(U["dl"], "Calculation Result"), G_BARS1,
        [act(SETV, WFVariableName="长边", WFInput=var("裁剪高"))],
        [act(SETV, WFVariableName="长边", WFInput=var("裁剪宽"))])
    bars_branch += [
        act(NUMBER, WFNumberActionNumber=BAR_RATIO, UUID=U["bar"]),
        act(MATH, WFMathOperation="×", WFInput=var("长边"),
            WFMathOperand=out(U["bar"], "Number"), UUID=U["bar2"]),
        act(SETV, WFVariableName="边条", WFInput=out(U["bar2"], "Calculation Result")),
        act(NUMBER, WFNumberActionNumber=2, UUID=U["b2n"]),
        act(MATH, WFMathOperation="×", WFInput=var("边条"),
            WFMathOperand=out(U["b2n"], "Number"), UUID=U["b2m"]),
        act(SETV, WFVariableName="双边", WFInput=out(U["b2m"], "Calculation Result")),
    ]
    bars_branch += math_to("+", "裁剪宽", "双边", "竖画布宽", U["pw"])   # 竖版:左右加边
    bars_branch += math_to("+", "裁剪高", "双边", "横画布高", U["ph"])   # 横版:上下加边
    # 画布尺寸按横竖取
    bars_branch += if_less_zero(
        out(U["dl"], "Calculation Result"), G_BARS2,
        [act(SETV, WFVariableName="画布宽", WFInput=var("竖画布宽")),
         act(SETV, WFVariableName="画布高", WFInput=var("裁剪高"))],
        [act(SETV, WFVariableName="画布宽", WFInput=var("裁剪宽")),
         act(SETV, WFVariableName="画布高", WFInput=var("横画布高"))])
    bars_branch += [
        # 黑底图: base64 → 解码 → 裁剪到画布尺寸(裁剪是已验证原子) → 叠加裁剪结果(居中)
        act("is.workflow.actions.gettext", WFTextActionText=text(BLACK_PNG_B64), UUID=U["blk"]),
        act("is.workflow.actions.base64encode", WFEncodeMode="Decode",
            WFBase64LineBreakMode="None", WFInput=out(U["blk"], "Text"), UUID=U["blkd"]),
        act("is.workflow.actions.image.crop",
            WFInput=out(U["blkd"], "Base64 Encoded"),
            WFImageCropPosition="Center",
            WFImageCropWidth=var("画布宽"), WFImageCropHeight=var("画布高"),
            UUID=U["rs"]),
        act("is.workflow.actions.overlayimageonimage",
            WFInput=out(U["rs"], "Cropped Image"),
            WFImage=var("裁剪结果"),
            WFShouldShowImageEditor=False, WFImagePosition="Center",
            UUID=U["ov"]),
        act(SETV, WFVariableName="成品", WFInput=out(U["ov"], "Overlaid Image")),
    ]
    if bars:
        body += bars_branch
    else:
        body.append(act(SETV, WFVariableName="成品", WFInput=var("裁剪结果")))
    if save:
        body.append(act("is.workflow.actions.savetocameraroll", WFInput=var("成品")))
    body.append(act(REPEAT, WFControlFlowMode=2, GroupingIdentifier=G_REP))
    return body


def limit27():
    return [
        act("is.workflow.actions.count", WFInput=var("图片"), UUID=U["c"]),
        act(SETV, WFVariableName="数量", WFInput=out(U["c"], "Count")),
        act("is.workflow.actions.list", WFItems=[var("数量"), 27], UUID=U["l1"]),
        act("is.workflow.actions.statistics", WFStatisticsOperation="Minimum",
            WFInput=out(U["l1"], "List"), UUID=U["s1"]),
        act(SETV, WFVariableName="上限", WFInput=out(U["s1"], "Statistics")),
        act("is.workflow.actions.getitemfromlist", WFInput=var("图片"),
            WFItemSpecifier="Items in Range", WFItemRangeStart=1,
            WFItemRangeEnd=var("上限"), UUID=U["g"]),
        act(SETV, WFVariableName="批量", WFInput=out(U["g"], "Item from List")),
    ]


def menu(prompt, items, varname, gid, uuids):
    a = [act(MENU, WFControlFlowMode=0, GroupingIdentifier=gid,
             WFMenuPrompt=prompt, WFMenuItems=[t for t, _ in items])]
    for (title, value), uuid in zip(items, uuids):
        a.append(act(MENU, WFControlFlowMode=1, GroupingIdentifier=gid,
                     WFMenuItemTitle=title))
        a.extend(set_num(varname, value, uuid))
    a.append(act(MENU, WFControlFlowMode=2, GroupingIdentifier=gid))
    return a


BASE = {
    "WFWorkflowClientVersion": "2302.0.4",
    "WFWorkflowClientRelease": "6.0",
    "WFWorkflowMinimumClientVersion": 900,
    "WFWorkflowMinimumClientVersionString": "900",
    "WFWorkflowIcon": {"WFWorkflowIconStartColor": 431817727,
                       "WFWorkflowIconGlyphNumber": 61440},
    "WFWorkflowImportQuestions": [],
    "WFWorkflowTypes": ["NCWidget", "WatchKit"],
    "WFWorkflowInputContentItemClasses": ["WFImageContentItem"],
}


def build(kind: str) -> list:
    if kind in ("full", "bars"):
        head = [
            act(IF, WFControlFlowMode=0, GroupingIdentifier=G_IF,
                WFCondition=100, WFInput=cond_input(SHORTCUT_INPUT)),  # 有任何值
            act(SETV, WFVariableName="图片", WFInput=SHORTCUT_INPUT),
            act(IF, WFControlFlowMode=1, GroupingIdentifier=G_IF),
            act("is.workflow.actions.selectphoto", WFSelectMultiplePhotos=True, UUID=U["sel"]),
            act(SETV, WFVariableName="图片", WFInput=out(U["sel"], "Photos")),
            act(IF, WFControlFlowMode=2, GroupingIdentifier=G_IF),
        ]
        return (head + limit27()
                + menu("选择裁剪比例", RATIOS, "比例", G_MENU1, [U_NUM[i] for i in range(1, 8)])
                + menu("选择裁剪位置", POSITIONS, "位置系数", G_MENU2, [U_NUM[8], U_NUM[9], U_NUM[10]])
                + crop_loop(save=True, bars=(kind == "bars")))
    # 测试版:比例2.71 + 位置靠下/靠右(系数1) + 加遮幅,输出结果供 CLI 校验
    return ([act(SETV, WFVariableName="图片", WFInput=SHORTCUT_INPUT)]
            + limit27()
            + set_num("比例", 2.71, U_NUM[1])
            + set_num("位置系数", 1, U_NUM[2])
            + crop_loop(save=False, bars=True))


def main():
    import os
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    for name, full in [("电影裁剪", "full"), ("电影裁剪遮幅", "bars"), ("电影裁剪-测试", "test")]:
        actions = build(full)
        unsigned = f"{name}-unsigned.shortcut"
        signed = f"{name}.shortcut"
        with open(unsigned, "wb") as f:
            plistlib.dump({**BASE, "WFWorkflowActions": actions}, f)
        subprocess.run(["plutil", "-lint", unsigned], check=True, capture_output=True)
        subprocess.run(["shortcuts", "sign", "--mode", "anyone",
                        "--input", unsigned, "--output", signed], check=True)
        print(f"{signed}: {len(actions)} 个动作,已签名")


if __name__ == "__main__":
    sys.exit(main())
