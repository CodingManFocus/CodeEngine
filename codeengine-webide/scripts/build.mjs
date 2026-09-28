import { build } from "esbuild";
import { mkdir, copyFile, rename, readFile, writeFile } from "node:fs/promises";
await mkdir("dist", { recursive: true });
const result = await build({
  entryPoints: ["src/main.tsx"],
  bundle: true,
  minify: true,
  outfile: "dist/app.js",
  jsx: "automatic",
  target: ["es2022"],
  metafile: true,
  legalComments: "inline",
  define: { "process.env.NODE_ENV": '"production"' },
});
await rename("dist/app.css", "dist/style.css");
await copyFile("index.html", "dist/index.html");
const packages = [
  ...new Set(
    Object.keys(result.metafile.inputs)
      .map((path) => path.match(/node_modules\/((?:@[^/]+\/)?[^/]+)/)?.[1])
      .filter(Boolean),
  ),
];
const licenses = await Promise.all(
  packages.sort().map(async (name) => {
    const pkg = JSON.parse(
      await readFile(`node_modules/${name}/package.json`, "utf8"),
    );
    for (const file of ["LICENSE", "LICENSE.txt", "LICENSE.md"]) {
      try {
        return `${name} ${pkg.version}\n${await readFile(`node_modules/${name}/${file}`, "utf8")}`;
      } catch {}
    }
    throw new Error(`Missing license: ${name}`);
  }),
);
await writeFile("dist/THIRD_PARTY_LICENSES.txt", licenses.join("\n\n---\n\n"));
console.log("Studio built; bundled dependency licenses included.");
