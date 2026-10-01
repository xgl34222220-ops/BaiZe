package io.github.xgl34222220.baize.root;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
public class FrozenReviewTreeTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final AtomicBoolean stopped=new AtomicBoolean();
    private Path root() throws Exception { return temp.newFolder().toPath(); }
    private Path file(Path root,String name,String content) throws Exception { Path file=root.resolve(name);Files.createDirectories(file.getParent());Files.write(file,content.getBytes());return file; }
    private FrozenReviewTree.Snapshot capture(Path root) { FrozenReviewTree.Snapshot s=FrozenReviewTree.capture(root,stopped,5000);assertTrue(s.reason,s.complete);return s; }
    private FrozenReviewTree.Result clean(FrozenReviewTree.Snapshot s,boolean deleteRoot) { return FrozenReviewTree.delete(s,deleteRoot,100000,stopped,5000,(path,dir)->true); }
    @Test public void newBackdatedContentNeverEntersTheReviewedManifest() throws Exception {
        Path root=root();Path original=file(root,"nested/old.bin","old");FrozenReviewTree.Snapshot s=capture(root);
        Path added=file(root,"nested/new.bin","new content");Files.setLastModifiedTime(added,FileTime.fromMillis(1000));
        FrozenReviewTree.Result r=clean(s,true);assertFalse(Files.exists(original));assertTrue(Files.exists(added));assertEquals(1,r.files);assertFalse(r.complete);
    }
    @Test public void rewritingInPlaceAndRestoringMtimeIsRejected() throws Exception {
        Path root=root();Path changed=file(root,"one.bin","old");FrozenReviewTree.Snapshot s=capture(root);FileTime time=Files.getLastModifiedTime(changed);
        Files.write(changed,"new".getBytes());Files.setLastModifiedTime(changed,time);FrozenReviewTree.Result r=clean(s,false);
        assertEquals(0,r.files);assertTrue(Files.exists(changed));assertFalse(r.complete);
    }
    @Test public void equalMetadataWithinOneTimestampTickStillRequiresOriginalContents() throws Exception {
        Path root=root();Path changed=file(root,"one.bin","old");FrozenReviewTree.Snapshot before=capture(changed);
        Files.write(changed,"new".getBytes());FrozenReviewTree.Snapshot after=capture(changed);
        org.json.JSONObject proof=FrozenReviewTree.toJson(before);
        proof.getJSONArray("entries").getJSONObject(0).put("stamp",after.entries.get(0).stamp);
        FrozenReviewTree.Result r=clean(FrozenReviewTree.fromJson(proof),true);
        assertEquals(0,r.files);assertEquals("new",new String(Files.readAllBytes(changed)));
        assertEquals("contents_changed_after_review",r.reason);
    }
    @Test public void parentReplacementAndSymlinksNeverReachOutsideFiles() throws Exception {
        Path root=root();file(root,"nested/one.bin","old");FrozenReviewTree.Snapshot s=capture(root);Path outside=root();Path keep=file(outside,"one.bin","outside");
        Files.move(root.resolve("nested"),root.resolve("moved"));Files.createSymbolicLink(root.resolve("nested"),outside);FrozenReviewTree.Result r=clean(s,false);
        assertEquals(0,r.files);assertEquals("outside",new String(Files.readAllBytes(keep)));assertFalse(r.complete);
    }
    @Test public void persistenceRetainsTheExactOriginalEntriesAndNoNewContent() throws Exception {
        Path root=root();Path selected=file(root,"selected.bin","selected");FrozenReviewTree.Snapshot s=capture(root);
        FrozenReviewTree.Snapshot restored=FrozenReviewTree.fromJson(FrozenReviewTree.toJson(s));assertNotNull(restored);
        Path keep=file(root,"unreviewed.bin","keep");FrozenReviewTree.Result r=clean(restored,false);
        assertFalse(Files.exists(selected));assertTrue(Files.exists(keep));assertEquals(1,r.files);
        assertEquals(0,clean(restored,false).files);assertTrue(Files.exists(keep));
    }
    @Test public void incompleteOrCancelledCaptureAuthorizesNothing() throws Exception {
        Path root=root();Path keep=file(root,"keep.bin","keep");FrozenReviewTree.Snapshot limited=FrozenReviewTree.capture(root,stopped,5000,1);
        assertFalse(limited.complete);assertEquals(0,clean(limited,true).files);assertTrue(Files.exists(keep));
        stopped.set(true);assertFalse(FrozenReviewTree.capture(root,stopped,5000).complete);
    }
    @Test public void validNestedFilesDeleteExactlyOnceAndPreserveConfiguredRoot() throws Exception {
        Path root=root();file(root,"a.bin","one");file(root,"nested/b.bin","two");FrozenReviewTree.Snapshot s=capture(root);
        FrozenReviewTree.Result r=clean(s,false);assertTrue(r.reason,r.complete);assertEquals(2,r.files);assertEquals(6,r.bytes);assertTrue(Files.isDirectory(root));assertEquals(0,clean(s,false).files);
    }
    @Test public void wholeDirectoryQuarantineProofRejectsNewContents() throws Exception {
        Path root=root();file(root,"old.bin","old");FrozenReviewTree.Snapshot s=capture(root);assertTrue(FrozenReviewTree.unchanged(s,stopped,5000));
        file(root,"new.bin","new");assertFalse(FrozenReviewTree.unchanged(s,stopped,5000));
    }
    @Test public void unavailableStorageParentIsNotReportedAsAnAlreadyDeletedTree() throws Exception {
        Path parent=root();Path root=Files.createDirectory(parent.resolve("target"));file(root,"old.bin","old");FrozenReviewTree.Snapshot s=capture(root);
        Files.move(parent,parent.resolveSibling(parent.getFileName()+"-moved"));FrozenReviewTree.Result r=clean(s,true);
        assertFalse(r.complete);assertTrue(r.failures>0);assertEquals(0,r.files);
    }
    @Test public void failedMutationRecordingStopsTheFollowingDeletion() throws Exception {
        Path root=root();file(root,"a.bin","one");file(root,"b.bin","two");FrozenReviewTree.Snapshot s=capture(root);
        FrozenReviewTree.Result r=FrozenReviewTree.delete(s,false,100000,stopped,5000,(p,d)->true,(p,d,b)->{throw new java.io.IOException("synthetic record failure");});
        assertEquals(1,r.files);try(java.util.stream.Stream<Path> remaining=Files.list(root)){assertEquals(1,remaining.count());}
        assertFalse(r.complete);assertEquals("result_recording_failed",r.reason);
    }
}
