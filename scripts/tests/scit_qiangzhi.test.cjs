// 四川工业科技学院专属适配（强智经典版）回归测试
// 数据：2026-11-11 取得的 xskbcx.aspx 个人课表页真实样本（整表 34 个课程块，
// 存档 ~/workspace/shangke-scit/xskbcx-real-sample-2026-11-11.md）。
// 通用版 qiangzhi.js 对这批真实格子的基线结果为 0/8 组全错（多块并一、节次取错、
// 双周不过滤、调课标记污染教室），本测试锁定专属脚本 scit.js 的定版行为。
'use strict';

global.window = { location: { href: '' } };
global.document = { body: { innerText: '' } };

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const adapter = require(path.join(__dirname, '..', '..', 'shared', 'assets', 'offline_repo', 'schools', 'resources', 'SCIT', 'scit.js'));
const { parseWeeks, classifyBlock, splitBlocksRaw } = adapter;

// 把一个格子的原始文字（换行分隔）解析成课程数组（day 由表格列决定，此处不验）
function parseCell(cellText, fallbackStart) {
  const out = [];
  for (const lines of splitBlocksRaw(cellText)) {
    const p = classifyBlock(lines, lines.join(' '), { start: fallbackStart, end: fallbackStart });
    if (p && p.weeks.length) out.push(p);
  }
  return out;
}

function expectCourses(cellText, fallbackStart, expected) {
  const got = parseCell(cellText, fallbackStart);
  assert.equal(got.length, expected.length, `课程数: got ${got.length}`);
  expected.forEach((e, i) => {
    const g = got[i];
    assert.equal(g.name, e.name, `[${i}] name`);
    assert.equal(g.teacher, e.teacher, `[${i}] teacher`);
    assert.equal(g.position, e.position, `[${i}] position`);
    assert.equal(g.startSection, e.s, `[${i}] startSection`);
    assert.equal(g.endSection, e.e, `[${i}] endSection`);
    assert.deepEqual(g.weeks, e.weeks, `[${i}] weeks`);
    if (e.remarkHas) assert.ok((g.remark || '').includes(e.remarkHas), `[${i}] remark 应含 ${e.remarkHas}`);
  });
}

const W = (a, b) => Array.from({ length: b - a + 1 }, (_, i) => a + i);
const WO = (a, b) => W(a, b).filter((w) => w % 2 === 1);
const WE = (a, b) => W(a, b).filter((w) => w % 2 === 0);

test('parseWeeks: 经典版竖线单双周与前缀剥离', () => {
  assert.deepEqual(parseWeeks('周三第3,4节{第2-18周|双周}'), WE(2, 18));
  assert.deepEqual(parseWeeks('周二第9,10节{第7-17周|单周}'), WO(7, 17));
  assert.deepEqual(parseWeeks('周五第1,2节{第3-3周}'), [3]);
  assert.deepEqual(parseWeeks('周五第9,10节{第6-6周|双周}'), [6]);
  assert.deepEqual(parseWeeks('3,7-17周单周'), [3, ...WO(7, 17)]);
});

test('parseWeeks: 通用新版格式不回归', () => {
  assert.deepEqual(parseWeeks('1-14,17-18(周)[01-02节]'), [...W(1, 14), 17, 18]);
  assert.deepEqual(parseWeeks('1-16周（单）'), WO(1, 16));
  assert.deepEqual(parseWeeks('1-16周'), W(1, 16));
});

test('周三第1节: 同格两块不合并', () => {
  expectCourses('算法与数据结构\n周三第1,2节{第2-4周}\n段理\nC603\n算法与数据结构\n周三第1,2节{第6-18周}\n段理\nC603', 1, [
    { name: '算法与数据结构', teacher: '段理', position: 'C603', s: 1, e: 2, weeks: W(2, 4) },
    { name: '算法与数据结构', teacher: '段理', position: 'C603', s: 1, e: 2, weeks: W(6, 18) },
  ]);
});

test('周二第9节: 一格三块(单周/单周/双周)', () => {
  expectCourses('算法与数据结构\n周二第9,10节{第3-3周|单周}\n段理\n钟灵楼（6号楼）203\n算法与数据结构\n周二第9,10节{第7-17周|单周}\n段理\n钟灵楼（6号楼）203\n大学英语AⅢ\n周二第9,10节{第2-18周|双周}\n杨宇婷\nC302', 9, [
    { name: '算法与数据结构', teacher: '段理', position: '钟灵楼（6号楼）203', s: 9, e: 10, weeks: [3] },
    { name: '算法与数据结构', teacher: '段理', position: '钟灵楼（6号楼）203', s: 9, e: 10, weeks: WO(7, 17) },
    { name: '大学英语AⅢ', teacher: '杨宇婷', position: 'C302', s: 9, e: 10, weeks: WE(2, 18) },
  ]);
});

test('调课标记: 从教室剥离进备注', () => {
  expectCourses('模拟电子技术\n周三第9,10节{第2-4周}\n汪美\n钟灵楼（6号楼）104 (调0340)、(调0343)', 9, [
    { name: '模拟电子技术', teacher: '汪美', position: '钟灵楼（6号楼）104', s: 9, e: 10, weeks: W(2, 4), remarkHas: '调0340' },
  ]);
  expectCourses('大学物理实验\n周一第7,8节{第7-18周}\n段红刚\nA203 (调0717)', 7, [
    { name: '大学物理实验', teacher: '段红刚', position: 'A203', s: 7, e: 8, weeks: W(7, 18), remarkHas: '调0717' },
  ]);
  expectCourses('毛泽东思想和中国特色社会主义理论体系概论\n周四第9,10节{第6-18周}\n熊瑶\n钟灵楼（6号楼）203 (调1183)', 9, [
    { name: '毛泽东思想和中国特色社会主义理论体系概论', teacher: '熊瑶', position: '钟灵楼（6号楼）203', s: 9, e: 10, weeks: W(6, 18), remarkHas: '调1183' },
  ]);
});

test('同课同节不同周不同教室: 分段两块各自独立', () => {
  expectCourses('模拟电子技术\n周三第7,8节{第3-3周}\n汪美\n毓秀105A\n模拟电子技术\n周三第7,8节{第4-4周}\n汪美\nC303', 7, [
    { name: '模拟电子技术', teacher: '汪美', position: '毓秀105A', s: 7, e: 8, weeks: [3] },
    { name: '模拟电子技术', teacher: '汪美', position: 'C303', s: 7, e: 8, weeks: [4] },
  ]);
});

test('其余真实格子抽查', () => {
  expectCourses('劳动教育\n周三第3,4节{第2-18周|双周}\n张波\nD404', 3, [
    { name: '劳动教育', teacher: '张波', position: 'D404', s: 3, e: 4, weeks: WE(2, 18) },
  ]);
  expectCourses('大学生心理健康（恋爱心理与防艾）\n周四第3,4节{第7-8周}\n张亚\n毓秀305', 3, [
    { name: '大学生心理健康（恋爱心理与防艾）', teacher: '张亚', position: '毓秀305', s: 3, e: 4, weeks: [7, 8] },
  ]);
  expectCourses('操作系统\n周五第3,4节{第6-18周}\n孙建波\n钟灵楼（6号楼）406\n操作系统\n周五第3,4节{第2-4周}\n孙建波\n钟灵楼（6号楼）406', 3, [
    { name: '操作系统', teacher: '孙建波', position: '钟灵楼（6号楼）406', s: 3, e: 4, weeks: W(6, 18) },
    { name: '操作系统', teacher: '孙建波', position: '钟灵楼（6号楼）406', s: 3, e: 4, weeks: W(2, 4) },
  ]);
  expectCourses('形势与政策Ⅲ\n周二第7,8节{第15-18周}\n赵萍\nD506\n大学生创新与创业Ⅰ\n周二第7,8节{第11-14周}\n刘婷\nD506', 7, [
    { name: '形势与政策Ⅲ', teacher: '赵萍', position: 'D506', s: 7, e: 8, weeks: W(15, 18) },
    { name: '大学生创新与创业Ⅰ', teacher: '刘婷', position: 'D506', s: 7, e: 8, weeks: W(11, 14) },
  ]);
  expectCourses('毛泽东思想和中国特色社会主义理论体系概论\n周五第7,8节{第3-3周|单周}\n熊瑶\n毓秀104\n毛泽东思想和中国特色社会主义理论体系概论\n周五第7,8节{第7-17周|单周}\n熊瑶\n毓秀104', 7, [
    { name: '毛泽东思想和中国特色社会主义理论体系概论', teacher: '熊瑶', position: '毓秀104', s: 7, e: 8, weeks: [3] },
    { name: '毛泽东思想和中国特色社会主义理论体系概论', teacher: '熊瑶', position: '毓秀104', s: 7, e: 8, weeks: WO(7, 17) },
  ]);
  expectCourses('大学物理实验\n周五第1,2节{第3-3周}\n段红刚\nA204', 1, [
    { name: '大学物理实验', teacher: '段红刚', position: 'A204', s: 1, e: 2, weeks: [3] },
  ]);
});
