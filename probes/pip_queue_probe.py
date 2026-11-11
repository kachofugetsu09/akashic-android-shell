"""编译真实 updateState 的队列顺序模型；不是 Android UI/像素验收。"""
from pathlib import Path
import subprocess
import sys

root, output = (Path(value).resolve() for value in sys.argv[1:3])
output.mkdir(parents=True, exist_ok=True)
source = (root / "app/src/main/java/com/akashic/shell/AgentPipView.kt").read_text()
body = source[source.index("    fun updateState("):source.index("    private fun applySize()")]
pull = "readState:" in body
only_null = len(sys.argv) > 3 and sys.argv[3] == "null"
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"


def jar(group, name, version):
    return next((cache / group / name / version).glob("*/*.jar"))


stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21")
annotations = jar("org.jetbrains", "annotations", "13.0")
compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.3.21"), stdlib,
            jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.3.21"),
            jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
            jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"), annotations]
enqueue = "view.updateState { current }" if pull else "view.updateState(current.first,current.second,current.third)"
(output / "Probe.kt").write_text('''
enum class PipMode { HIDDEN, RUNNING, NEEDS_YOU, DONE }
class Bitmap(val id:Int) { fun recycle() {} }
object BitmapFactory { fun decodeByteArray(bytes:ByteArray,offset:Int,size:Int):Bitmap? =
    if(bytes[0].toInt()==0) null else Bitmap(bytes[0].toInt()) }
class Animator { fun removeCallbacks(task:Runnable) {}; fun post(task:Runnable) {} }
class ProbeView {
    private var mode=PipMode.HIDDEN
    private var frame:Bitmap?=null
    private var statusText=""
    private var expanded=false
    private var breathAlpha=1f
    private val animator=Animator()
    private val breatheTick=Runnable {}
    private var visibility=0
    private val GONE=0
    private val VISIBLE=1
    private val pending=ArrayDeque<()->Unit>()
    fun post(task:()->Unit) { pending.add(task) }
    fun drain() { while(pending.isNotEmpty()) pending.removeFirst().invoke() }
    fun snapshot()=Triple(mode,statusText,frame?.id)
    private fun applySize() {}
    private fun invalidate() {}
''' + body + '''
}
fun main() {
    val view=ProbeView()
    var current=Triple(PipMode.RUNNING,"old",byteArrayOf(1) as ByteArray?)
    ENQUEUE
    ENQUEUE
    current=Triple(PipMode.HIDDEN,"stopped",null)
    view.drain()
    println("queued-old-running="+view.snapshot())
    if(CHECK_QUEUE) check(view.snapshot()==Triple(PipMode.HIDDEN,"stopped",null))
    current=Triple(PipMode.RUNNING,"attached",byteArrayOf(2))
    ENQUEUE
    view.drain()
    check(view.snapshot()==Triple(PipMode.RUNNING,"attached",2))
    current=Triple(PipMode.NEEDS_YOU,"takeover",byteArrayOf(3))
    ENQUEUE
    view.drain()
    check(view.snapshot()==Triple(PipMode.NEEDS_YOU,"takeover",3))
    current=Triple(PipMode.RUNNING,"old-update",byteArrayOf(4))
    ENQUEUE
    current=Triple(PipMode.DONE,"done",byteArrayOf(3))
    ENQUEUE
    view.drain()
    check(view.snapshot()==Triple(PipMode.DONE,"done",3))
    current=Triple(PipMode.RUNNING,"new-reader",null)
    ENQUEUE
    view.drain()
    println("new-reader-null="+view.snapshot())
    check(view.snapshot()==Triple(PipMode.RUNNING,"new-reader",null))
    println("PASS attach/update/NEEDS_YOU/DONE/new-reader with held UI queue")
}
'''.replace("ENQUEUE", enqueue).replace("CHECK_QUEUE", str(not only_null).lower()))
subprocess.run(["java", "-cp", ":".join(map(str, compiler)),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                "-classpath", f"{stdlib}:{annotations}", "-d", str(output / "classes"),
                str(output / "Probe.kt")], check=True)
subprocess.run(["java", "-cp", f"{output / 'classes'}:{stdlib}", "ProbeKt"], check=True)
