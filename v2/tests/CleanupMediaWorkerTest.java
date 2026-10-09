import io.github.xgl34222220.baize.root.CleanupMediaWorker;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CleanupMediaWorkerTest {
    static int assertions;
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static void expectInvalid(String path, String user) {
        try { CleanupMediaWorker.target(path, user); throw new AssertionError("accepted: " + path); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    static class Fake implements CleanupMediaWorker.Backend {
        final List<String> calls = new ArrayList<>();
        boolean fail, reappear; int inspections;
        public CleanupMediaWorker.Presence presence(CleanupMediaWorker.Target t) {
            inspections++;
            return reappear && inspections > 1 ? CleanupMediaWorker.Presence.PRESENT : CleanupMediaWorker.Presence.ABSENT;
        }
        public String refresh(CleanupMediaWorker.Target t, long remaining) {
            calls.add(t.user + ":" + t.path);
            return fail ? "INDEX_UNCONFIRMED" : "VERIFIED_ABSENT";
        }
    }
    static Path batch(Path state, String name, String... paths) throws Exception {
        Path dir = state.resolve("cleanup-media").resolve(name); Files.createDirectories(dir);
        Files.write(dir.resolve("user"), "10\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(dir.resolve("owner"), "999999999\n1\n".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String path : paths) { bytes.write(path.getBytes(StandardCharsets.UTF_8)); bytes.write(0); }
        Files.write(dir.resolve("paths.nul"), bytes.toByteArray()); return dir;
    }
    static String progress(Path batch) throws IOException {
        Path log = batch.resolve("progress.log");
        return Files.exists(log) ? Files.readString(log) : "";
    }
    static Path state(Path root, String name) throws Exception { return Files.createDirectory(root.resolve(name)); }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("hold")) {
            try (FileChannel channel = FileChannel.open(Paths.get(args[1]), StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                System.out.println("LOCKED"); System.out.flush(); Thread.sleep(60000);
            }
            return;
        }
        Path root = Paths.get(args[0]); Files.createDirectories(root);
        String suffix = "/Download/a space ' quote\n尾部\n.apk";
        CleanupMediaWorker.Target target = CleanupMediaWorker.target("/data/media/10" + suffix, "0");
        check(target.path.equals("/storage/emulated/10" + suffix) && target.user.equals("10"), "raw mapping/user/suffix");
        check(CleanupMediaWorker.scanArguments(target).get(9).equals(target.path), "argv suffix preserved");
        String where = CleanupMediaWorker.queryArguments(target).get(9);
        check(where.equals("_data='" + target.path.replace("'", "''") + "'"), "SQL quotes escaped without shell");
        check(CleanupMediaWorker.target("/mnt/media_rw/ABCD-0123/a", "10").user.equals("10"), "frozen removable user");
        check(CleanupMediaWorker.target("/mnt/installer/10/emulated/10/a", "0").user.equals("10"), "matching alias user");
        check(CleanupMediaWorker.target("/sdcard/a", "10").path.equals("/storage/emulated/10/a"), "sdcard user");
        expectInvalid("/mnt/media_rw/ABCD-0123/a", "unknown");
        expectInvalid("/mnt/installer/10/emulated/0/a", "0");
        expectInvalid("/data/media/0/../a", "0");
        check(CleanupMediaWorker.scanReply(0, "Result: Bundle[{android.intent.extra.STREAM=null}]"), "scan reply only transport");
        check(!CleanupMediaWorker.noRows(0, "Row: 0 _id=12"), "existing row is not success");
        check(!CleanupMediaWorker.noRows(0, "Error accessing provider"), "provider error exit0");
        check(CleanupMediaWorker.noRows(0, "No result found."), "exact no-row reply");

        // Exercise actual host filesystem evidence without weakening production path allowlists.
        java.lang.reflect.Constructor<CleanupMediaWorker.Target> ctor = CleanupMediaWorker.Target.class
            .getDeclaredConstructor(String.class, String.class, String.class);
        ctor.setAccessible(true);
        Path storage = Files.createDirectory(root.resolve("storage"));
        CleanupMediaWorker.Target owned = ctor.newInstance(storage.resolve("missing").toString(), storage.toString(), "0");
        check(CleanupMediaWorker.inspect(owned) == CleanupMediaWorker.Presence.ABSENT, "readable root and missing leaf");
        Files.writeString(storage.resolve("present"), "new file");
        owned = ctor.newInstance(storage.resolve("present").toString(), storage.toString(), "0");
        check(CleanupMediaWorker.inspect(owned) == CleanupMediaWorker.Presence.PRESENT, "existing files preserved");
        Files.createSymbolicLink(storage.resolve("link"), storage.resolve("missing"));
        owned = ctor.newInstance(storage.resolve("link").toString(), storage.toString(), "0");
        check(CleanupMediaWorker.inspect(owned) == CleanupMediaWorker.Presence.SYMLINK, "dangling symlink is not absent");
        owned = ctor.newInstance(root.resolve("unmounted/a").toString(), root.resolve("unmounted").toString(), "0");
        check(CleanupMediaWorker.inspect(owned) == CleanupMediaWorker.Presence.UNKNOWN, "missing volume is unknown");
        Files.createSymbolicLink(root.resolve("alias"), storage);
        owned = ctor.newInstance(root.resolve("alias/a").toString(), root.resolve("alias").toString(), "0");
        check(CleanupMediaWorker.inspect(owned) == CleanupMediaWorker.Presence.SYMLINK, "root alias cannot be followed");

        Class<?> cb = Class.forName("io.github.xgl34222220.baize.root.CleanupMediaWorker$ContentBackend");
        java.lang.reflect.Constructor<?> cbConstructor = cb.getDeclaredConstructor(Path.class);
        cbConstructor.setAccessible(true);
        Object backend = cbConstructor.newInstance(storage);
        java.lang.reflect.Method command = cb.getDeclaredMethod("command", List.class, long.class);
        command.setAccessible(true);
        long commandStart = System.nanoTime();
        Object timed = command.invoke(backend, Arrays.asList("/bin/sh", "-c", "exec sleep 5"), 100L);
        java.lang.reflect.Field code = timed.getClass().getDeclaredField("code"); code.setAccessible(true);
        check(code.getInt(timed) == -1, "timed-out content subprocess fails");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - commandStart) < 2500, "subprocess timeout bounded");

        Path first = state(root, "first"); batch(first, "pending-one", "/data/media/0/a", "/data/media/10/b");
        Fake fake = new Fake(); check(new CleanupMediaWorker(first, fake).runRound() == 2, "two attempts");
        check(progress(first.resolve("cleanup-media/completed/done-one")).contains("A 0 VERIFIED_ABSENT\n"), "checkpoint and done");
        check(!Files.exists(first.resolve("cleanup-media/completed/done-one/ack-0")), "no per-item ack file");
        new CleanupMediaWorker(first, fake).runRound(); check(fake.calls.size() == 2, "no replay ack");

        Path partial = state(root, "partial"); Path two = batch(partial, "pending-two", "/data/media/0/a", "/data/media/0/b");
        Files.write(two.resolve("ack-0"), "VERIFIED_ABSENT\n".getBytes());
        Fake partialFake = new Fake(); new CleanupMediaWorker(partial, partialFake).runRound();
        check(partialFake.calls.size() == 1 && partialFake.calls.get(0).endsWith("/b"), "partial success survives restart");
        Path partialDone = partial.resolve("cleanup-media/completed/done-two");
        check(!Files.exists(partialDone.resolve("ack-0")) && progress(partialDone).contains("A 0 VERIFIED_ABSENT\n") &&
            progress(partialDone).contains("A 16 VERIFIED_ABSENT\n"), "legacy ack compacted into progress log");

        Path bounded = state(root, "bounded"); String[] many = new String[40];
        for(int i=0;i<many.length;i++) many[i]="/data/media/0/"+i;
        batch(bounded,"pending-many",many); Fake boundedFake=new Fake();
        check(new CleanupMediaWorker(bounded,boundedFake).runRound()==32,"32 item bound");
        check(new CleanupMediaWorker(bounded,boundedFake).runRound()==8,"remaining checkpoint");
        check(boundedFake.calls.size()==40,"no duplicate success");

        Path retry = state(root,"retry");batch(retry,"pending-retry","/data/media/0/a");
        Fake failed=new Fake();failed.fail=true;new CleanupMediaWorker(retry,failed).runRound();
        check(progress(retry.resolve("cleanup-media/inflight-retry")).startsWith("R 0 "),"failed retained");
        check(!Files.exists(retry.resolve("cleanup-media/inflight-retry/retry-0")),"no per-item retry file");
        check(new CleanupMediaWorker(retry,failed).runRound()==0,"backoff respected");
        batch(retry,"pending-next","/data/media/0/b");Fake next=new Fake();new CleanupMediaWorker(retry,next).runRound();
        check(next.calls.size()==1 && next.calls.get(0).endsWith("/b"),"failed batch cannot starve next");

        Path changed=state(root,"changed");batch(changed,"pending-changed","/data/media/0/a");
        Fake appeared=new Fake();appeared.reappear=true;new CleanupMediaWorker(changed,appeared).runRound();
        check(!progress(changed.resolve("cleanup-media/inflight-changed")).contains("A 0 "),"reappearance never acked");
        check(progress(changed.resolve("cleanup-media/inflight-changed")).contains("CHANGED_DURING_REFRESH"),"reappearance explained");


        // Legacy retry file is honoured (backoff) and folded into the log.
        Path legacyRetry=state(root,"legacyretry");Path lr=batch(legacyRetry,"inflight-lr","/data/media/0/a");
        Files.writeString(lr.resolve("retry-0"),(System.currentTimeMillis()+600000)+"\n3\nINDEX_UNCONFIRMED\n");
        Fake lrFake=new Fake();check(new CleanupMediaWorker(legacyRetry,lrFake).runRound()==0 && lrFake.calls.isEmpty(),"legacy backoff honoured");
        check(!Files.exists(lr.resolve("retry-0")) && progress(lr).startsWith("R 0 ") && progress(lr).contains(" 3 INDEX_UNCONFIRMED"),"legacy retry compacted");
        // Torn final line from a killed process is ignored, not fatal.
        Path torn=state(root,"torn");Path tb=batch(torn,"inflight-torn","/data/media/0/a","/data/media/0/b");
        Files.writeString(tb.resolve("progress.log"),"A 0 VERIFIED_ABSENT\nA 1");
        Fake tornFake=new Fake();new CleanupMediaWorker(torn,tornFake).runRound();
        check(tornFake.calls.size()==1 && tornFake.calls.get(0).endsWith("/b"),"torn progress line ignored");

        // Completed receipts stay bounded once legacy history is migrated; never before.
        Path keep=state(root,"keep");
        for(int i=0;i<40;i++){
            Path b=batch(keep,"pending-k"+i,"/data/media/0/k"+i);
            Files.setLastModifiedTime(b,java.nio.file.attribute.FileTime.fromMillis(1000000L+i));
        }
        Path completedDir=Files.createDirectories(keep.resolve("cleanup-media/completed")); // legacy history, unmigrated
        Fake keepFake=new Fake();for(int i=0;i<3;i++) new CleanupMediaWorker(keep,keepFake).runRound();
        long unmigrated;try(var s=Files.list(completedDir)){unmigrated=s.filter(x->x.getFileName().toString().startsWith("done-")).count();}
        check(keepFake.calls.size()==40 && unmigrated==40,"no pruning before migration marker");
        check(Files.exists(first.resolve("cleanup-media/completed/.compacted-v1")),"fresh history needs no migration");
        Files.createFile(completedDir.resolve(".compacted-v1"));
        batch(keep,"pending-last","/data/media/0/last");new CleanupMediaWorker(keep,keepFake).runRound();
        long kept;try(var s=Files.list(completedDir)){kept=s.filter(x->x.getFileName().toString().startsWith("done-")).count();}
        check(kept==32 && Files.exists(completedDir.resolve("done-last")),"receipts capped at 32, newest kept");

        // One-time migration: legacy receipts compacted losslessly, overflow archived then removed.
        Path mig=state(root,"migrate");Path migDone=mig.resolve("cleanup-media/completed");Files.createDirectories(migDone);
        for(int i=0;i<40;i++){
            Path d=Files.createDirectory(migDone.resolve("done-m"+i));
            Files.writeString(d.resolve("user"),"0\n");Files.writeString(d.resolve("owner"),"1\n1\n");
            Files.write(d.resolve("paths.nul"),("/data/media/0/m"+i+"\0").getBytes(StandardCharsets.UTF_8));
            for(int k=0;k<5;k++) Files.writeString(d.resolve("ack-"+(k*20)),"VERIFIED_ABSENT\n");
            Files.setLastModifiedTime(d,java.nio.file.attribute.FileTime.fromMillis(1000000000L+i*1000L));
        }
        check(CleanupMediaWorker.migrate(mig)==8,"overflow receipts archived");
        long left;try(var st=Files.list(migDone)){left=st.filter(x->x.getFileName().toString().startsWith("done-")).count();}
        check(left==32 && Files.exists(migDone.resolve("done-m39")) && !Files.exists(migDone.resolve("done-m0")),"newest receipts kept");
        check(!Files.exists(migDone.resolve("done-m39/ack-0")) && progress(migDone.resolve("done-m39")).contains("A 80 VERIFIED_ABSENT\n"),"kept receipt compacted losslessly");
        check(Files.getLastModifiedTime(migDone.resolve("done-m39")).toMillis()==1000000000L+39000L,"receipt time preserved");
        check(Files.exists(migDone.resolve(".compacted-v1")),"marker written");
        Path archiveDir=mig.resolve("cleanup-media/archive");Path archive;
        try(var st=Files.list(archiveDir)){archive=st.filter(x->x.toString().endsWith(".tar")).findFirst().orElseThrow();}
        Path extract=Files.createDirectory(root.resolve("extract"));
        Process tar=new ProcessBuilder("tar","-xf",archive.toString(),"-C",extract.toString()).inheritIO().start();
        check(tar.waitFor()==0,"system tar reads archive");
        check(Files.readString(extract.resolve("done-m0/paths.nul")).equals("/data/media/0/m0\0") &&
            Files.readString(extract.resolve("done-m0/progress.log")).contains("A 40 VERIFIED_ABSENT\n"),"archived receipt restorable");
        check(CleanupMediaWorker.migrate(mig)==0,"migration runs once");
        Path unknown=state(root,"unknown");Path invalid=batch(unknown,"pending-invalid","/mnt/media_rw/ABCD-0123/a");
        Files.writeString(invalid.resolve("user"),"unknown\n");Fake unknownFake=new Fake();new CleanupMediaWorker(unknown,unknownFake).runRound();
        check(unknownFake.calls.isEmpty(),"unknown user cannot scan user0");
        Path malformed=state(root,"malformed");Path bad=batch(malformed,"pending-bad","/data/media/0/a");
        Files.write(bad.resolve("paths.nul"),new byte[]{(byte)0xff,0});Fake malformedFake=new Fake();new CleanupMediaWorker(malformed,malformedFake).runRound();
        check(malformedFake.calls.isEmpty() && progress(malformed.resolve("cleanup-media/inflight-bad")).startsWith("R 0 "),"invalid UTF8 retained");

        Path orphan=state(root,"orphan");Path orphanBatch=batch(orphan,".building-dead","/data/media/0/a");
        Fake recovered=new Fake();new CleanupMediaWorker(orphan,recovered).runRound();check(recovered.calls.size()==1,"dead producer recovered");
        Path live=state(root,"live");Path liveBatch=batch(live,".building-live","/data/media/0/a");
        long pid=ProcessHandle.current().pid();String stat=Files.readString(Paths.get("/proc",Long.toString(pid),"stat"));
        String ticks=stat.substring(stat.lastIndexOf(')')+2).split(" ")[19];Files.writeString(liveBatch.resolve("owner"),pid+"\n"+ticks+"\n");
        check(new CleanupMediaWorker(live,new Fake()).runRound()==0,"live producer not claimed");

        Path locked=state(root,"locked");Path writerBatch=batch(locked,".building-writer","/data/media/0/a");
        Process holder=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-XX:-UsePerfData","-cp",System.getProperty("java.class.path"),
            CleanupMediaWorkerTest.class.getName(),"hold",writerBatch.resolve("paths.nul").toString()).start();
        check(new BufferedReader(new InputStreamReader(holder.getInputStream())).readLine().equals("LOCKED"),"child writer ready");
        check(new CleanupMediaWorker(locked,new Fake()).runRound()==0,"cross-process native-style writer lock blocks consumer");
        holder.destroyForcibly();check(holder.waitFor(5,TimeUnit.SECONDS),"writer killed");
        check(new CleanupMediaWorker(locked,new Fake()).runRound()==1,"kernel releases dead writer lock");

        Path global=state(root,"global");batch(global,"pending-global","/data/media/0/a");Path globalLock=global.resolve("cleanup-media/consumer.lock");Files.createFile(globalLock);
        Process consumer=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-XX:-UsePerfData","-cp",System.getProperty("java.class.path"),
            CleanupMediaWorkerTest.class.getName(),"hold",globalLock.toString()).start();
        check(new BufferedReader(new InputStreamReader(consumer.getInputStream())).readLine().equals("LOCKED"),"consumer child locked");
        check(new CleanupMediaWorker(global,new Fake()).runRound()==0,"concurrent consumer excluded");
        consumer.destroyForcibly();consumer.waitFor(5,TimeUnit.SECONDS);
        check(new CleanupMediaWorker(global,new Fake()).runRound()==1,"dead consumer recovery");
        System.out.println("CleanupMediaWorker: "+assertions+" assertions passed");
    }
}
