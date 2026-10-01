#!/usr/bin/env python3
"""Real C fcntl/Java FileChannel handoff races on owned fixture files only."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
C_HEADER = '''#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
'''
SHIM = r'''
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
int fcntl(int fd, int cmd, ...) {
  va_list a; va_start(a,cmd); void *arg=va_arg(a,void*); va_end(a);
  int (*real)(int,int,...)=dlsym(RTLD_NEXT,"fcntl");
  if(cmd==F_SETLK && getenv("GATE_OPENED")) {
    int marker=open(getenv("GATE_OPENED"),O_CREAT|O_WRONLY,0600); if(marker>=0)close(marker);
    struct timespec delay={0,10000000};
    while(access(getenv("GATE_RELEASE"),F_OK))nanosleep(&delay,0);
  }
  return real(fd,cmd,arg);
}
'''

class MediaHandoff(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='baize-media-handoff-')
        cls.build=Path(cls.temp.name)
        src=cls.build/'writer.c'
        src.write_text(C_HEADER+f'#include "{ROOT}/v2/native/cleanup_media_output.h"\n'+r'''
int main(int argc,char **argv) {
 if(argc!=4)return 90;
 FILE *out=open_media_output(argv[1]);
 int code=13;
 if(out) { code=unlink(argv[2]) ? 12 : 0; if(!code)fwrite(argv[2],1,strlen(argv[2])+1,out); fclose(out); }
 FILE *result=fopen(argv[3],"w"); if(result){fprintf(result,"%d\n",code);fclose(result);} return code;
}
''')
        cls.writer=cls.build/'writer'
        subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(src),'-o',str(cls.writer)],check=True)
        shim=cls.build/'gate.c';shim.write_text(SHIM);cls.shim=cls.build/'gate.so'
        subprocess.run(['cc','-shared','-fPIC',str(shim),'-ldl','-o',str(cls.shim)],check=True)
        compiler=['javac'] if shutil.which('javac') else ['java','-XX:-UsePerfData','-m','jdk.compiler/com.sun.tools.javac.Main']
        subprocess.run(compiler+['-d',str(cls.build),str(ROOT/'v2/app/src/main/java/io/github/xgl34222220/baize/root/CleanupMediaWorker.java')],check=True)
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()
    def wait_file(self,path):
        deadline=time.monotonic()+8
        while not path.exists() and time.monotonic()<deadline: time.sleep(.01)
        self.assertTrue(path.exists(),str(path))
    def test_killed_parent_and_late_writer_cannot_delete_after_empty_batch_claim(self):
        case=Path(tempfile.mkdtemp(dir=self.build));state=case/'state';batch=state/'cleanup-media/.building-late';batch.mkdir(parents=True)
        nul=batch/'paths.nul';nul.touch();(batch/'user').write_text('0\n')
        target=case/'owned.apk';target.write_bytes(b'original content')
        opened,release,result=[case/name for name in ('opened','release','result')]
        env={**os.environ,'LD_PRELOAD':str(self.shim),'GATE_OPENED':str(opened),'GATE_RELEASE':str(release)}
        # The shell is a distinct parent, not exec. Its child has already opened
        # the NUL inode but is paused immediately before its first F_SETLK.
        shell=subprocess.Popen(['bash','-c','"$1" "$2" "$3" "$4" & wait','fixture',str(self.writer),str(nul),str(target),str(result)],env=env)
        stat=Path(f'/proc/{shell.pid}/stat').read_text();ticks=stat[stat.rfind(')')+2:].split()[19]
        (batch/'owner').write_text(f'{shell.pid}\n{ticks}\n')
        try:
            self.wait_file(opened);shell.kill();shell.wait(timeout=5)
            subprocess.run(['java','-XX:-UsePerfData','-cp',str(self.build),
                            'io.github.xgl34222220.baize.root.CleanupMediaWorker',str(state)],check=True,timeout=5)
            self.assertFalse(batch.exists())
            self.assertTrue((state/'cleanup-media/completed/done-late/paths.nul').exists())
            release.touch();self.wait_file(result)
            self.assertEqual('13',result.read_text().strip(),'late writer must reject old fd after original path moves')
            self.assertEqual(b'original content',target.read_bytes())
            self.assertEqual(b'',(state/'cleanup-media/completed/done-late/paths.nul').read_bytes())
        finally:
            release.touch()
            if shell.poll() is None:shell.kill();shell.wait()

if __name__=='__main__':unittest.main(verbosity=2)
