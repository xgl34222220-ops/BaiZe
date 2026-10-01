package io.github.xgl34222220.baize.root;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable reviewed entries. Cleanup never discovers additional files. */
public final class FrozenReviewTree {
    private FrozenReviewTree() {}
    public static final class Entry {
        final String path, key, stamp;
        final boolean directory;
        final long bytes;
        Entry(String path, String key, String stamp, boolean directory, long bytes) {
            this.path=path; this.key=key; this.stamp=stamp; this.directory=directory; this.bytes=bytes;
        }
    }
    public static final class Snapshot {
        public final String root;
        final List<Entry> entries = new ArrayList<>();
        final Map<String,String> parents = new LinkedHashMap<>();
        public boolean complete = true;
        public String reason = "";
        public long bytes, files, directories;
        public int count() { return entries.size(); }
        Snapshot(String root) { this.root=root; }
        void fail(String reason) { complete=false; if (this.reason.isEmpty()) this.reason=reason; }
    }
    public static final class Result {
        public long bytes, files, directories;
        public int failures;
        public boolean complete = true;
        public String reason = "";
        public final List<String> deletedPaths = new ArrayList<>();
        void retained(String reason, boolean error) { complete=false; if (this.reason.isEmpty()) this.reason=reason; if(error) failures++; }
    }
    public interface Policy { boolean allow(String path, boolean directory); }
    public interface MutationObserver { void deleted(String path, boolean directory, long bytes) throws IOException; }
    private static final int MAX_ENTRIES = 100_000;
    private static boolean anchorAvailable(Snapshot snapshot) {
        try(SecureDirectoryStream<Path> ignored=openDirectory(Paths.get(snapshot.root).getParent(),snapshot.parents)) {
            return true;
        } catch(Exception error) { return false; }
    }
    public static boolean unchanged(Snapshot before, AtomicBoolean cancelled, long budgetMs) {
        if(before==null || !before.complete) return false;
        Snapshot after=capture(Paths.get(before.root),cancelled,budgetMs);
        if(!after.complete || after.entries.size()!=before.entries.size() || !after.parents.equals(before.parents)) return false;
        Map<String,String> expected=new HashMap<>();
        for(Entry entry:before.entries) expected.put(entry.path,entry.stamp);
        for(Entry entry:after.entries) if(!entry.stamp.equals(expected.get(entry.path))) return false;
        return true;
    }
    private static Entry read(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if(a.isSymbolicLink() || (!a.isRegularFile() && !a.isDirectory()) || a.fileKey()==null)
            throw new IOException("special_or_unidentified_file");
        String key = a.fileKey().toString();
        Object changed = Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
        String stamp = key + ":" + a.size() + ":" + a.lastModifiedTime() + ":" + changed;
        return new Entry(path.toString(), key, stamp, a.isDirectory(), a.isRegularFile()?a.size():0L);
    }
    public static Snapshot capture(Path requested, AtomicBoolean cancelled, long budgetMs) {
        return capture(requested,cancelled,budgetMs,MAX_ENTRIES);
    }
    public static Snapshot capture(Path requested, AtomicBoolean cancelled, long budgetMs, int maxEntries) {
        Path root=requested.toAbsolutePath().normalize();
        Snapshot result=new Snapshot(root.toString());
        long deadline=System.nanoTime()+Math.min(300_000L,Math.max(1L,budgetMs))*1_000_000L;
        try {
            if(!root.toString().equals(requested.toAbsolutePath().toString()) || root.getNameCount()==0) throw new IOException("invalid_root");
            for(Path parent=root.getParent();parent!=null;parent=parent.getParent()) {
                Entry e=read(parent); if(!e.directory) throw new IOException("invalid_parent"); result.parents.put(e.path,e.key);
            }
            ArrayDeque<Path> pending=new ArrayDeque<>(); pending.push(root);
            while(!pending.isEmpty()) {
                if(cancelled.get() || Thread.currentThread().isInterrupted()) { result.fail("cancelled"); break; }
                if(System.nanoTime()>=deadline || result.entries.size()>=Math.min(MAX_ENTRIES,maxEntries)) { result.fail("snapshot_limit"); break; }
                Path path=pending.pop(); Entry entry=read(path); result.entries.add(entry);
                if(entry.directory) {
                    result.parents.put(entry.path,entry.key); result.directories++;
                    try(SecureDirectoryStream<Path> directory=openDirectory(path,result.parents)) {
                        for(Path child:directory) {
                            if(pending.size()+result.entries.size()>=Math.min(MAX_ENTRIES,maxEntries)) throw new IOException("snapshot_limit");
                            pending.push(path.resolve(child.getFileName()));
                        }
                    }
                } else { result.files++; result.bytes+=entry.bytes; }
            }
        } catch(Exception error) { result.fail(error instanceof NoSuchFileException?"missing":"unreadable_or_changed"); }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> openDirectory(Path path, Map<String,String> expected) throws IOException {
        DirectoryStream<Path> initial=Files.newDirectoryStream(path.getRoot());
        if(!(initial instanceof SecureDirectoryStream)) { initial.close(); throw new IOException("secure_traversal_unsupported"); }
        SecureDirectoryStream<Path> current=(SecureDirectoryStream<Path>)initial;
        Path walked=path.getRoot();
        try {
            for(Path part:path) {
                SecureDirectoryStream<Path> next=current.newDirectoryStream(part,LinkOption.NOFOLLOW_LINKS);
                current.close(); current=next; walked=walked.resolve(part);
                String key=expected.get(walked.toString());
                if(key==null || !key.equals(current.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey().toString()))
                    throw new IOException("parent_changed");
            }
            return current;
        } catch(Exception error) { current.close(); throw error; }
    }
    public static Result delete(Snapshot snapshot, boolean deleteRoot, long maxBytes, AtomicBoolean cancelled, long budgetMs, Policy policy) {
        return delete(snapshot,deleteRoot,maxBytes,cancelled,budgetMs,policy,(path,directory,bytes)->{});
    }
    public static Result delete(Snapshot snapshot, boolean deleteRoot, long maxBytes, AtomicBoolean cancelled, long budgetMs,
                                Policy policy, MutationObserver observer) {
        Result result=new Result();
        if(snapshot==null || !snapshot.complete || snapshot.entries.isEmpty()) { result.retained("original_snapshot_unavailable",true); return result; }
        long deadline=System.nanoTime()+Math.min(300_000L,Math.max(1L,budgetMs))*1_000_000L;
        for(int i=snapshot.entries.size()-1;i>=0;i--) {
            Entry expected=snapshot.entries.get(i);
            if(expected.path.equals(snapshot.root) && expected.directory && !deleteRoot) continue;
            if(cancelled.get() || Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline) { result.retained("stopped",false); break; }
            if(!policy.allow(expected.path,expected.directory) || (!expected.directory && expected.bytes>maxBytes)) {
                result.retained("protected",false); continue;
            }
            Path path=Paths.get(expected.path);
            try(SecureDirectoryStream<Path> parent=openDirectory(path.getParent(),snapshot.parents)) {
                BasicFileAttributes relative=parent.getFileAttributeView(path.getFileName(),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
                Entry now=read(path);
                if(relative.fileKey()==null || !relative.fileKey().toString().equals(expected.key) ||
                   !now.key.equals(expected.key) || now.directory!=expected.directory || (!expected.directory && !now.stamp.equals(expected.stamp))) {
                    result.retained("changed_after_review",false); continue;
                }
                BasicFileAttributes last=parent.getFileAttributeView(path.getFileName(),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
                if(last.fileKey()==null || !last.fileKey().toString().equals(expected.key) || last.isSymbolicLink() ||
                   (!expected.directory && (last.size()!=expected.bytes || !last.lastModifiedTime().equals(relative.lastModifiedTime())))) {
                    result.retained("changed_after_review",false); continue;
                }
                if(expected.directory) { parent.deleteDirectory(path.getFileName()); result.directories++; }
                else { parent.deleteFile(path.getFileName()); result.files++; result.bytes+=expected.bytes; result.deletedPaths.add(expected.path); }
                try { observer.deleted(expected.path,expected.directory,expected.bytes); }
                catch(IOException unavailable) { result.retained("result_recording_failed",true); break; }
            } catch(NoSuchFileException missing) {
                if(!anchorAvailable(snapshot)) result.retained("storage_parent_unavailable",true);
            } catch(DirectoryNotEmptyException changed) { result.retained("new_or_protected_contents",false);
            } catch(Exception error) { result.retained("unreadable_or_delete_failed",true); }
        }
        if(!deleteRoot && snapshot.entries.get(0).directory) {
            try(SecureDirectoryStream<Path> directory=openDirectory(Paths.get(snapshot.root),snapshot.parents)) {
                if(directory.iterator().hasNext()) result.retained("new_or_protected_contents",false);
            } catch(NoSuchFileException missing) {
                if(!anchorAvailable(snapshot)) result.retained("storage_parent_unavailable",true);
            } catch(Exception error) { result.retained("unreadable_or_delete_failed",true); }
        }
        return result;
    }
    public static JSONObject toJson(Snapshot snapshot) throws org.json.JSONException {
        JSONObject result=new JSONObject().put("version",1).put("root",snapshot.root).put("complete",snapshot.complete).put("reason",snapshot.reason);
        JSONArray entries=new JSONArray();
        for(Entry entry:snapshot.entries) entries.put(new JSONObject().put("path",entry.path).put("key",entry.key).put("stamp",entry.stamp)
            .put("directory",entry.directory).put("bytes",entry.bytes));
        return result.put("entries",entries).put("parents",new JSONObject(snapshot.parents));
    }
    public static Snapshot fromJson(JSONObject raw) {
        if(raw==null || raw.optInt("version")!=1) return null;
        try {
            Snapshot result=new Snapshot(raw.getString("root"));
            if(!raw.getBoolean("complete")) return null;
            JSONArray entries=raw.getJSONArray("entries");
            if(entries.length()==0 || entries.length()>MAX_ENTRIES) return null;
            Set<String> seen=new HashSet<>();
            for(int i=0;i<entries.length();i++) {
                JSONObject e=entries.getJSONObject(i); String path=e.getString("path");
                if(!(path.equals(result.root)||path.startsWith(result.root+"/")) || !Paths.get(path).normalize().toString().equals(path) || !seen.add(path)) return null;
                Entry entry=new Entry(path,e.getString("key"),e.getString("stamp"),e.getBoolean("directory"),e.getLong("bytes"));
                if(entry.key.isEmpty() || entry.stamp.isEmpty() || entry.bytes<0) return null;
                result.entries.add(entry); if(entry.directory) result.directories++; else {result.files++;result.bytes+=entry.bytes;}
            }
            if(!result.entries.get(0).path.equals(result.root)) return null;
            JSONObject parents=raw.getJSONObject("parents"); Iterator<String> keys=parents.keys();
            while(keys.hasNext()) {String path=keys.next(); if(!path.startsWith("/") || !Paths.get(path).normalize().toString().equals(path)) return null;result.parents.put(path,parents.getString(path));}
            return result;
        } catch(Exception error) { return null; }
    }
}
