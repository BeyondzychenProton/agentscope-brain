import assert from "node:assert/strict";
import test from "node:test";
import { splitAssistantContent, toolLabel } from "./assistant-content.ts";

test("closed tool call keeps the reply that follows it", () => {
  const parts = splitAssistantContent(`正在为你查找「张三」的人员信息。

<tool_call> {"name": "identity_resolve", "arguments": {"name": "张三"}} </tool_call>
张三可以参加。`);

  assert.deepEqual(parts.map(part => part.type), ["text", "tool", "text"]);
  assert.equal(parts[0].type === "text" && parts[0].text.includes("正在为你查找"), true);
  assert.equal(parts[1].type === "tool" && parts[1].name, "identity_resolve");
  assert.equal(parts[2].type === "text" && parts[2].text.includes("张三可以参加"), true);
  assert.equal(parts[1].type === "tool" && toolLabel(parts[1].name, parts[1].arguments), "查找人员：张三");
});

test("an unfinished tool tag does not swallow earlier prose", () => {
  const parts = splitAssistantContent("正在查找。\n<tool_call> {\"name\":");
  assert.equal(parts[0].type === "text" && parts[0].text.includes("正在查找"), true);
  assert.equal(parts[1].type === "tool" && parts[1].pending, true);
  assert.equal(JSON.stringify(parts).includes("<tool_call>"), false);
});

test("plain prose stays one text segment", () => {
  const parts = splitAssistantContent("董佳怡的手机号是 13800000000");
  assert.deepEqual(parts, [{ type: "text", text: "董佳怡的手机号是 13800000000" }]);
});
