import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { IntelligenceEngine } from "../src/intelligence/engine";
import { parseJar } from "../src/intelligence/jar";
import type { JavaClass, JavaMember } from "../src/intelligence/types";
import { fieldType, methodType, typeText } from "../src/intelligence/javaTypes";

const jar = Buffer.from(
  readFileSync(
    new URL("fixtures/browser-api.jar.base64", import.meta.url),
    "utf8",
  ),
  "base64",
);
const fixture = await parseJar(
  jar.buffer.slice(jar.byteOffset, jar.byteOffset + jar.byteLength),
);
function member(
  name: string,
  descriptor: string,
  extra: Partial<JavaMember> = {},
): JavaMember {
  return { name, descriptor, access: 1, deprecated: false, ...extra };
}
function javaClass(
  name: string,
  methods: JavaMember[] = [],
  extra: Partial<JavaClass> = {},
): JavaClass {
  return {
    name,
    superName: "java.lang.Object",
    interfaces: [],
    access: 1,
    deprecated: false,
    fields: [],
    methods,
    ...extra,
  };
}
const standard = [
  javaClass("java.lang.Object", [member("toString", "()Ljava/lang/String;")], {
    superName: undefined,
  }),
  javaClass("java.lang.String", [
    member("length", "()I"),
    member("substring", "(I)Ljava/lang/String;"),
  ]),
  javaClass(
    "java.util.List",
    [
      member("get", "(I)Ljava/lang/Object;", { signature: "(I)TE;" }),
      member("size", "()I"),
    ],
    { signature: "<E:Ljava/lang/Object;>Ljava/lang/Object;", access: 513 },
  ),
  javaClass("example.PlayerList", [], {
    signature: "Ljava/lang/Object;Ljava/util/List<Lorg/bukkit/entity/Player;>;",
    interfaces: ["java.util.List"],
  }),
  javaClass("example.Factory", [
    member("choose", "(I)Lorg/bukkit/entity/Player;", { access: 9 }),
    member("choose", "(Ljava/lang/String;)Ljava/lang/String;", { access: 9 }),
    member("identity", "(Ljava/lang/Object;)Ljava/lang/Object;", {
      access: 9,
      signature: "<T:Ljava/lang/Object;>(TT;)TT;",
    }),
    member("first", "([Ljava/lang/Object;)Ljava/lang/Object;", {
      access: 137,
      signature: "<T:Ljava/lang/Object;>([TT;)TT;",
    }),
    member(
      "either",
      "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
      { access: 9, signature: "<T:Ljava/lang/Object;>(TT;TT;)TT;" },
    ),
    member("overloaded", "(I)Lorg/bukkit/entity/Player;", { access: 9 }),
    member("overloaded", "(J)Lorg/bukkit/entity/Player;", { access: 9 }),
  ]),
];
const engine = new IntelligenceEngine([...fixture, ...standard]);
function complete(source: string, service = engine) {
  const position = source.indexOf("|");
  assert.notEqual(position, -1);
  const clean = source.replace("|", "");
  return service.complete(clean, position);
}
function labels(source: string, service = engine) {
  return complete(source, service)?.options.map((entry) => entry.label) ?? [];
}

test("uses real compiled JAR event methods and chained return types", () => {
  assert.ok(
    labels("module demo; on PlayerJoinEvent event { event.| }").includes(
      "getPlayer",
    ),
  );
  const members = labels(
    "module demo; on PlayerJoinEvent event { event.getPlayer().| }",
  );
  assert.ok(members.includes("getHealth"));
  assert.ok(members.includes("sendMessage"), "inherits CommandSender members");
  assert.ok(members.includes("toString"), "inherits Object members");
  assert.ok(
    !members.includes("broadcastMessage"),
    "never offers unrelated classes' methods",
  );
});
test("completes qualified import names and inserts qualified names for non-imported types", () => {
  const result = complete("module demo; use org.bukkit.entity.P|")!;
  assert.equal(result.options[0].label, "org.bukkit.entity.Player");
  assert.equal("module demo; use ".length, result.from);
  const factory = complete("module demo; enable { Fac| }")!.options.find(
    (option) => option.label === "Factory",
  );
  assert.equal(factory?.apply, "example.Factory");
});
test("distinguishes static and instance methods and preserves overloads", () => {
  assert.ok(labels("module demo; enable { Bukkit.| }").includes("getPlayer"));
  assert.ok(!labels("module demo; enable { Player.| }").includes("getHealth"));
  const choose = complete(
    "module demo; use example.Factory; enable { Factory.cho| }",
  )!.options[0];
  assert.match(choose.detail, /2 overloads/);
  assert.match(choose.info!, /choose\(int/);
  assert.match(choose.info!, /choose\(String/);
});
test("resolves state, function parameters and function result types", () => {
  assert.ok(
    labels(
      "module demo; state Player owner; fn greet(Player target) -> Player { target.| return target; }",
    ).includes("getHealth"),
  );
  assert.ok(
    labels("module demo; state Player owner; enable { owner.| }").includes(
      "getHealth",
    ),
  );
  assert.ok(
    labels(
      "module demo; fn who(Player target) -> Player { return target; } enable { who(null).| }",
    ).includes("getHealth"),
  );
});
test("command implicit variables match JavaEmitter and do not leak", () => {
  assert.ok(
    labels("module demo; command hello { sender.| }").includes("sendMessage"),
  );
  assert.deepEqual(
    labels("module demo; command hello {} enable { sender.| }"),
    [],
  );
  assert.ok(labels("module demo; command hello { args.| }").includes("length"));
  assert.ok(
    labels("module demo; command hello { args[0].| }").includes("substring"),
  );
});
test("infers var from JAR return type, preserves lexical scope and declaration order", () => {
  assert.ok(
    labels(
      "module demo; on PlayerJoinEvent event { var p = event.getPlayer(); p.| }",
    ).includes("getHealth"),
  );
  assert.deepEqual(
    labels(
      "module demo; on PlayerJoinEvent event { p.| var p = event.getPlayer(); }",
    ),
    [],
  );
  assert.deepEqual(
    labels(
      "module demo; on PlayerJoinEvent event { { var p = event.getPlayer(); } p.| }",
    ),
    [],
  );
  assert.ok(
    labels(
      "module demo; on PlayerJoinEvent event { Player p; { String p; p.| } }",
    ).includes("length"),
  );
  assert.ok(
    !labels(
      "module demo; on PlayerJoinEvent event { Player p; { String p; p.| } }",
    ).includes("getHealth"),
  );
});
test("suppresses comments, strings and text blocks without losing offsets", () => {
  for (const source of [
    "module demo; enable { // Bukkit.|",
    "module demo; enable { /* Bukkit.| */ }",
    'module demo; enable { String text = "Bukkit.|"; }',
    'module demo; enable { String text = """\nBukkit.|\n"""; }',
  ])
    assert.equal(complete(source), null);
  assert.ok(
    labels(
      "module demo; on PlayerJoinEvent event { /* } */ event.| }",
    ).includes("getPlayer"),
  );
});
test("substitutes class type variables and generic inherited parents", () => {
  assert.ok(
    labels(
      "module demo; enable { List<Player> players; players.get(0).| }",
    ).includes("getHealth"),
  );
  assert.ok(
    labels(
      "module demo; use example.PlayerList; enable { PlayerList players; players.get(0).| }",
    ).includes("getHealth"),
  );
  assert.deepEqual(
    labels("module demo; enable { List players; players.get(0).| }"),
    [],
    "unbound generic types stay unknown",
  );
});
test("selects compatible overload results and avoids guessing ambiguous results", () => {
  assert.ok(
    labels(
      "module demo; use example.Factory; enable { Factory.choose(1).| }",
    ).includes("getHealth"),
  );
  assert.ok(
    labels(
      'module demo; use example.Factory; enable { Factory.choose("one").| }',
    ).includes("length"),
  );
  assert.deepEqual(
    labels(
      "module demo; use example.Factory; enable { Factory.choose(unknown).| }",
    ),
    [],
  );
  assert.ok(
    labels(
      "module demo; use example.Factory; on PlayerJoinEvent event { Factory.identity(event.getPlayer()).| }",
    ).includes("getHealth"),
  );
});
test("hover shows actual signatures and signature help handles nested calls", () => {
  const source =
    'module demo; on PlayerJoinEvent event { event.getPlayer().sendMessage("hi"); }';
  const hover = engine.hover(source, source.indexOf("sendMessage") + 3);
  assert.match(hover?.text ?? "", /void sendMessage\(String/);
  assert.match(hover?.text ?? "", /org.bukkit/);
  const call = "module demo; use example.Factory; enable { Factory.choose(";
  assert.match(engine.signature(call, call.length)?.text ?? "", /choose\(int/);
  assert.match(
    engine.signature(call, call.length)?.text ?? "",
    /choose\(String/,
  );
});
test("diagnostics only assert members for complete indexed hierarchies", () => {
  const source =
    "module demo; on PlayerJoinEvent event { event.getPlayer().doesNotExist(); }";
  assert.equal(engine.diagnostics(source).length, 1);
  const partial = new IntelligenceEngine(fixture);
  assert.equal(
    partial.diagnostics(source).length,
    0,
    "missing Object suppresses certainty",
  );
  assert.equal(
    engine.diagnostics(
      'module demo; use other.Plugin from "Plugin"; enable { unknown.missing(); }',
    ).length,
    0,
  );
  assert.match(
    engine.diagnostics("module demo; use other.Missing;")[0].message,
    /API index/,
  );
});
test("for-loop variables end at the loop's body", () => {
  assert.ok(
    labels(
      "module demo; enable { for (Player player : players) { player.| } }",
    ).includes("getHealth"),
  );
  assert.deepEqual(
    labels("module demo; enable { for (Player player : players) {} player.| }"),
    [],
  );
});
test("JVM signature rendering includes generics, arrays, variance and method variables", () => {
  assert.equal(
    typeText(
      fieldType(
        "Ljava/util/List;",
        "Ljava/util/List<+Lorg/bukkit/entity/Player;>;",
      ),
    ),
    "List<? extends Player>",
  );
  const identity = methodType(
    "(Ljava/lang/Object;)Ljava/lang/Object;",
    "<T:Ljava/lang/Object;>(TT;)TT;",
  );
  assert.deepEqual(identity.variables, ["T"]);
  assert.equal(typeText(identity.result), "T");
  assert.equal(typeText(fieldType("[[Ljava/lang/String;")), "String[][]");
});

test("unsupported initializer expressions never borrow the last operand's type", () => {
  assert.deepEqual(
    labels(
      'module demo; on PlayerJoinEvent event { var ambiguous = unknown ? "text" : event.getPlayer(); ambiguous.| }',
    ),
    [],
  );
  assert.deepEqual(
    labels(
      'module demo; on PlayerJoinEvent event { var text = "text" + event.getPlayer(); text.| }',
    ),
    [],
  );
});

test("plugin-owned imports never borrow Paper classes with identical binary names", () => {
  assert.deepEqual(
    labels(
      'module demo; use org.bukkit.entity.Player from "CustomProvider"; enable { Player p; p.| }',
    ),
    [],
  );
  assert.deepEqual(
    labels(
      'module demo; use org.bukkit.Bukkit from "CustomProvider"; enable { Bukkit.| }',
    ),
    [],
  );
  assert.deepEqual(
    labels(
      'module demo; use org.bukkit.Bukkit from "CustomProvider"; enable { org.bukkit.Bukkit.| }',
    ),
    [],
  );
  assert.ok(
    !labels(
      'module demo; use org.bukkit.entity.Player from "CustomProvider"; enable { Pla| }',
    ).includes("Player"),
  );
  assert.equal(
    engine.diagnostics(
      'module demo; use org.bukkit.entity.Player from "CustomProvider"; enable { Player p; p.providerOnlyMethod(); }',
    ).length,
    0,
  );
});

test("generic member caches preserve external provider identity", () => {
  assert.ok(
    labels(
      "module demo; enable { List<Player> values; values.get(0).| }",
    ).includes("getHealth"),
  );
  assert.deepEqual(
    labels(
      'module demo; use org.bukkit.entity.Player from "CustomProvider"; enable { List<Player> values; values.get(0).| }',
    ),
    [],
  );
});

test("generic varargs bind array element types and conflicting arguments stay unknown", () => {
  assert.ok(
    labels(
      "module demo; use example.Factory; enable { Player[] players; Factory.first(players).| }",
    ).includes("getHealth"),
  );
  assert.ok(
    labels(
      "module demo; use example.Factory; enable { Player player; Factory.first(player).| }",
    ).includes("getHealth"),
  );
  assert.deepEqual(
    labels(
      'module demo; use example.Factory; enable { Player player; Factory.either(player, "text").| }',
    ),
    [],
  );
  assert.ok(
    labels(
      "module demo; use example.Factory; enable { Factory.choose(0xDEAD).| }",
    ).includes("getHealth"),
  );
});
test("nested JVM class signatures do not mix outer and inner type arguments", () => {
  assert.equal(
    typeText(
      fieldType(
        "Lexample/Outer$Inner;",
        "Lexample/Outer<Ljava/lang/String;>.Inner<Lorg/bukkit/entity/Player;>;",
      ),
    ),
    "Outer.Inner<Player>",
  );
});
