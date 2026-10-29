import assert from "node:assert/strict";
import test from "node:test";
import { visibleAssistantText, toolLabel } from "./assistant-content.ts";

test("legacy tool markup is removed without inventing execution status", () => {
  assert.equal(visibleAssistantText('之前<tool_call>{"name":"identity_resolve"}</tool_call>最终答复'), '之前最终答复');
  assert.equal(visibleAssistantText('之前<tool>{"name":"identity_resolve"}</tool>最终答复'), '之前最终答复');
});
test("every split of a legacy tag stays outside visible prose", () => {
  for (const tag of ['<tool_call>{"name":', '<tool>{"name":']) {
    for (let i=1; i<=tag.length; i++) assert.equal(visibleAssistantText('之前'+tag.slice(0,i)), '之前');
  }
});
test("native tool aliases preserve underscores inside the canonical tool name", () => {
  assert.equal(toolLabel('calendar_find_slots', {}), '查找空闲时段');
  assert.equal(toolLabel('calendar_get_freebusy', {}), '查询忙闲');
  assert.equal(toolLabel('identity_resolve', { name: '合成人员' }), '查找人员：合成人员');
});
test("normal markdown stays unchanged", () => {
  const text='**答复**\n\n时间 < 3 小时'; assert.equal(visibleAssistantText(text), text);
});
