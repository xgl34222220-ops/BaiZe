package io.github.xgl34222220.baize.root;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class MediaQueueLeaseTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void onlyOneConsumerOwnsBatchAndLeaseInodePersists() throws Exception {
        File state=temp.newFolder();File file=new File(state,"organizer-media-consumer.lease");Object inode;
        try(MediaQueueLease first=MediaQueueLease.acquire(state)) {assertNotNull(first);assertNull(MediaQueueLease.acquire(state));inode=Files.readAttributes(file.toPath(),"unix:ino").get("ino");}
        try(MediaQueueLease second=MediaQueueLease.acquire(state)) {assertNotNull(second);assertEquals(inode,Files.readAttributes(file.toPath(),"unix:ino").get("ino"));}
        assertTrue(file.isFile());
    }
    @Test public void invalidStateIsNotAcquired() throws Exception {try {MediaQueueLease.acquire(temp.newFile());fail();}catch(IOException expected){}}
}
