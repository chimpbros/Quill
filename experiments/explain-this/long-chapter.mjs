// A single live smoke test of the app's unchanged whole-chapter summarization request.
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { collectResponse } from './evaluate.mjs';
import { signIn } from './subscription.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const source=await readFile(resolve(root,'aira/src/main/java/com/yugentech/quill/aira/provider/PromptLibrary.kt'),'utf8');
const prefixLiteral=source.match(/private const val QUICK_GROUNDING = ("(?:\\.|[^"\\])*")/)?.[1];
const template=source.match(/"SUMMARIZE_CHAPTER" -> """\s*([\s\S]*?)\s*"""\.trimIndent\(\)/)?.[1];
assert.ok(prefixLiteral && template,'Could not extract app summary instructions.');
const chapter=[
 'At dawn, Mara found the village footbridge broken. She marked the unsafe crossing with a red lantern and waited on the village side of the river.',
 ...Array.from({length:300},(_,i)=>`Observation ${i+1}: Mara checked the bank and noted the water against the old stone markers. The wind rustled the reeds. She waited for help and kept the lantern beside the closed crossing.`),
 'At noon, Ivo arrived with a replacement beam. Mara and Ivo carried it to the damaged span and secured it together. They did not cross while the beam was loose.',
 ...Array.from({length:100},(_,i)=>`Repair note ${i+1}: Mara and Ivo inspected the ropes and supports from the village side. They checked the beam again before allowing anyone to use the footbridge.`),
 'At sunset, Mara and Ivo finished securing the bridge. Mara crossed first to test it, returned safely, and changed the red lantern to a green one. The chapter ends with the villagers using the reopened crossing.',
].join('\n\n');
const body={model:'gpt-6-luna',store:false,stream:true,instructions:JSON.parse(prefixLiteral)+template.replaceAll('\r\n','\n').trim().replaceAll('${title}','The Lantern Bridge (synthetic test chapter)').replaceAll('${author}','Test fixture'),input:[{role:'user',content:`PASSAGES:\n${chapter}\nREQUEST:\n`}]};
assert.ok(Buffer.byteLength(chapter,'utf8')>28672);
const output=resolve(root,'artifacts/prompt-eval',`long-chapter-${new Date().toISOString().replaceAll(':','-').replaceAll('.','-')}`);
await mkdir(output,{recursive:true});
await writeFile(resolve(output,'request.json'),JSON.stringify(body,null,2));
const status={liveModelTest:false,chapterBytes:Buffer.byteLength(chapter,'utf8'),outputDirectory:output};
await writeFile(resolve(output,'status.json'),JSON.stringify(status,null,2));
console.log(`Prepared ${status.chapterBytes}-byte synthetic chapter. Output: ${output}`);
let session;
try {
 session=await signIn(root);
 const catalog=await fetch('https://api.openai.com/v1/models',{headers:{Authorization:`Bearer ${session.accessToken}`},signal:AbortSignal.timeout(30000)});
 assert.ok(catalog.ok,'Could not fetch authorized models.');
 assert.ok((await catalog.json()).models?.some(model=>model.slug===body.model && model.visibility==='list'),'GPT-6 Luna is not available; no substitute model was used.');
 const response=await fetch('https://api.openai.com/v1/responses',{method:'POST',headers:{Authorization:`Bearer ${session.accessToken}`,'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(120000)});
 const answer=await collectResponse(response);
 await writeFile(resolve(output,'answer.json'),JSON.stringify(answer,null,2));
 assert.ok(/Mara/.test(answer.text) && /Ivo/.test(answer.text) && /green/i.test(answer.text),'Review required: summary did not cover the main characters and ending.');
 status.liveModelTest=true;status.state='completed';
 console.log('Whole-chapter summary completed and includes the ending.');
} catch(error) {
 status.state='failed';status.failure=error.message;console.error(error.message);process.exitCode=1;
} finally {
 if(session)status.sessionRevocationConfirmed=await session.close();
 await writeFile(resolve(output,'status.json'),JSON.stringify(status,null,2));
}
