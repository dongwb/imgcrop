#!/usr/bin/env python3
"""生成「电影裁剪」iOS 快捷指令(plist → 签名)。

用法: python3 build_shortcut.py
产物: 电影裁剪.shortcut(正式版,已签名)、电影裁剪-测试.shortcut(固定 2.71,用于 CLI 实测)

序列化规则的权威依据见 memory: shortcut-serialization-rules
- 条件动作: WFCondition 为整数(0=< 4== 100=有任何值), WFNumberValue 为字符串,
  WFInput 需 {"Type":"Variable","Variable":{...}} 嵌套包装
- 动作输出引用: ActionOutput + OutputUUID + OutputName 三件套
- 省略 WFInput = 空输入(无隐式上一步传递)
- 数字入变量必须经「数字」动作中转
- 统计动作在循环内不工作 → 循环内取最小值用 If 比较
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

G_IF, G_MENU, G_REP = ("11111111-1111-4111-8111-111111111111",
                       "22222222-2222-4222-8222-222222222222",
                       "33333333-3333-4333-8333-333333333333")
G_ORIENT = "66666666-6666-4666-8666-666666666666"
G_IFW, G_IFH = ("44444444-4444-4444-8444-444444444444",
                "55555555-5555-4555-8555-555555555555")

# 产出动作 UUID(引用用)
U = {k: f"0A0000{i:02X}-0000-4000-8000-0000000000{i:02X}" for i, k in enumerate(
    ["sel", "c", "l1", "s1", "g", "w", "h", "o", "n1", "inv",
     "m1", "m2", "d1", "d2", "cr"], start=1)}
# 菜单各比例对应的 Number 动作 UUID
U_NUM = {i: f"0B0000{i:02X}-0000-4000-8000-0000000000{i:02X}" for i in range(1, 9)}

RATIOS = [("电影画幅 2.71:1", 2.71), ("宽银幕 2.39:1", 2.39), ("16:9", 1.7778),
          ("4:3", 1.3333), ("1:1 正方形", 1.0), ("3:4", 0.75), ("9:16 IG快拍", 0.5625)]


def set_ratio(value, uuid):
    """比例入变量必须经「数字」动作中转(直接塞 real 会得到空值)"""
    return [act(NUMBER, WFNumberActionNumber=value, UUID=uuid),
            act(SETV, WFVariableName="比例", WFInput=out(uuid, "Number"))]


def if_less_than_zero(input_att, gid, true_actions, false_actions):
    """现代条件格式: 整数条件 + 字符串数值 + 嵌套输入"""
    return ([act(IF, WFControlFlowMode=0, GroupingIdentifier=gid,
                 WFCondition=0, WFNumberValue="0", WFInput=cond_input(input_att))]
            + true_actions
            + [act(IF, WFControlFlowMode=1, GroupingIdentifier=gid)]
            + false_actions
            + [act(IF, WFControlFlowMode=2, GroupingIdentifier=gid)])


def minvar(result, a, b, gid, duuid):
    """result = min(a, b) —— 统计动作在循环内不工作,用 If 比较差值"""
    return ([act(MATH, WFMathOperation="-", WFInput=var(a),
                 WFMathOperand=var(b), UUID=duuid)]
            + if_less_than_zero(out(duuid, "Calculation Result"), gid,
                                [act(SETV, WFVariableName=result, WFInput=var(a))],
                                [act(SETV, WFVariableName=result, WFInput=var(b))]))


def crop_loop(save):
    """逐张:横版用所选比例,竖版自动用倒数比例,等比最大居中裁剪"""
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
    body += if_less_than_zero(
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
    body += [
        act(MATH, WFMathOperation="×", WFInput=var("图高"),
            WFMathOperand=var("实际比例"), UUID=U["m1"]),
        act(SETV, WFVariableName="目标宽", WFInput=out(U["m1"], "Calculation Result")),
        act(MATH, WFMathOperation="÷", WFInput=var("图宽"),
            WFMathOperand=var("实际比例"), UUID=U["m2"]),
        act(SETV, WFVariableName="目标高", WFInput=out(U["m2"], "Calculation Result")),
    ]
    body += minvar("裁剪宽", "图宽", "目标宽", G_IFW, U["d1"])
    body += minvar("裁剪高", "图高", "目标高", G_IFH, U["d2"])
    body.append(act("is.workflow.actions.image.crop", WFInput=REPEAT_ITEM,
                    WFImageCropPosition="Center",
                    WFImageCropWidth=var("裁剪宽"), WFImageCropHeight=var("裁剪高"),
                    UUID=U["cr"]))
    if save:
        body.append(act("is.workflow.actions.savetocameraroll",
                        WFInput=out(U["cr"], "Cropped Image")))
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


def menu():
    a = [act(MENU, WFControlFlowMode=0, GroupingIdentifier=G_MENU,
             WFMenuPrompt="选择裁剪比例", WFMenuItems=[t for t, _ in RATIOS])]
    for i, (title, value) in enumerate(RATIOS, start=1):
        a.append(act(MENU, WFControlFlowMode=1, GroupingIdentifier=G_MENU,
                     WFMenuItemTitle=title))
        a.extend(set_ratio(value, U_NUM[i]))
    a.append(act(MENU, WFControlFlowMode=2, GroupingIdentifier=G_MENU))
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


def build(full: bool) -> list:
    if full:
        head = [
            act(IF, WFControlFlowMode=0, GroupingIdentifier=G_IF,
                WFCondition=100, WFInput=cond_input(SHORTCUT_INPUT)),  # 有任何值
            act(SETV, WFVariableName="图片", WFInput=SHORTCUT_INPUT),
            act(IF, WFControlFlowMode=1, GroupingIdentifier=G_IF),
            act("is.workflow.actions.selectphoto", WFSelectMultiplePhotos=True, UUID=U["sel"]),
            act(SETV, WFVariableName="图片", WFInput=out(U["sel"], "Photos")),
            act(IF, WFControlFlowMode=2, GroupingIdentifier=G_IF),
        ]
        return head + limit27() + menu() + crop_loop(save=True)
    return ([act(SETV, WFVariableName="图片", WFInput=SHORTCUT_INPUT)]
            + limit27() + set_ratio(2.71, U_NUM[8]) + crop_loop(save=False))


def main():
    import os
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    for name, full in [("电影裁剪", True), ("电影裁剪-测试", False)]:
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
