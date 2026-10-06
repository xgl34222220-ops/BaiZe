#!/usr/bin/env python3
"""FIPS SHA256 vectors, random/boundary streams, cancellation, and 10k cache cost.
All paths are owned temporary fixtures. No Android path allowlist is weakened.
"""
import hashlib, os, pathlib, random, subprocess, tempfile, time, unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class Sha256(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(prefix='baize-sha-');cls.root=pathlib.Path(cls.tmp.name)
        harness=cls.root/'hash.c'
        harness.write_text('''#define _GNU_SOURCE
#include <stdio.h>
#include <fcntl.h>
#include <sys/stat.h>
'''+f'#include "{ROOT}/v2/native/baize_sha256.h"\n'+r'''
static int abort_hash(void *ctx){return ctx?9:0;}
int main(int argc,char **argv){if(argc<2)return 2;int fd=open(argv[1],O_RDONLY);struct stat st;if(fd<0||fstat(fd,&st))return 3;
char out[65]={0};int code=baize_sha256_fd(fd,(uint64_t)st.st_size,out,abort_hash,argc>2?(void*)1:NULL);close(fd);
if(code)return code;if(!baize_sha256_hex_valid(out))return 4;puts(out);return 0;}
''')
        cls.hash=cls.root/'hash';cls.engine=cls.root/'engine'
        for src,binary in ((harness,cls.hash),(ROOT/'v2/native/baize_engine_42_4.c',cls.engine)):
            subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror','-Wno-misleading-indentation',str(src),'-o',str(binary)],check=True)
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def digest(self,data):
        file=self.root/'input';file.write_bytes(data)
        return subprocess.check_output([str(self.hash),str(file)],text=True).strip()
    def test_known_vectors(self):
        known=[(b'','e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'),
               (b'abc','ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'),
               (b'abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq','248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1'),
               (b'a'*1000000,'cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0')]
        for data,expected in known:self.assertEqual(expected,self.digest(data))
    def test_python_hashlib_random_and_stream_boundaries(self):
        rng=random.Random(257)
        sizes=[1,2,31,55,56,57,63,64,65,127,128,129,65535,65536,65537,1048577]+[rng.randrange(0,400000) for _ in range(40)]
        for n in sizes:
            data=rng.randbytes(n)
            with self.subTest(size=n):self.assertEqual(hashlib.sha256(data).hexdigest(),self.digest(data))
    def test_cancelled_hash_emits_no_digest(self):
        file=self.root/'input';file.write_bytes(b'preserve')
        p=subprocess.run([str(self.hash),str(file),'cancel'],capture_output=True,text=True)
        self.assertEqual(9,p.returncode);self.assertEqual('',p.stdout);self.assertEqual(b'preserve',file.read_bytes())
    def test_ten_thousand_small_files_original_hash_scan_and_clean(self):
        case=self.root/'performance';cache=case/'data/media/0/Android/data/com.example.fixture/cache';cache.mkdir(parents=True)
        for n in range(10000):(cache/f'{n}.bin').write_bytes((str(n).encode()+b'x'*64)[:64])
        whitelist=case/'whitelist';whitelist.touch();packages=case/'packages';packages.touch()
        common=['--data-root',str(case/'data'),'--media-root',str(case/'data/media'),'--whitelist',str(whitelist),'--package-whitelist',str(packages)]
        scan=[str(self.engine),'scan-cache',*common,'--report',str(case/'scan.tsv'),'--targets',str(case/'targets'),
              '--items',str(case/'items'),'--manifest',str(case/'manifest'),'--summary',str(case/'scan.env')]
        start=time.monotonic();subprocess.run(scan,check=True,timeout=40);scan_ms=(time.monotonic()-start)*1000
        self.assertIn('files=10000\n',(case/'scan.env').read_text())
        clean=[str(self.engine),'clean-cache-snapshot',*common,'--manifest',str(case/'manifest'),'--report',str(case/'clean.tsv'),
               '--summary',str(case/'clean.env'),'--deleted-nul',str(case/'deleted.nul')]
        start=time.monotonic();subprocess.run(clean,check=True,timeout=40);clean_ms=(time.monotonic()-start)*1000
        self.assertIn('files=10000\n',(case/'clean.env').read_text());self.assertEqual([],list(cache.iterdir()))
        self.assertEqual(10000,(case/'deleted.nul').read_bytes().count(b'\0'))
        print(f'HOST 10000 x64B full SHA256: scan={scan_ms:.1f}ms clean={clean_ms:.1f}ms; not Android timing')
if __name__=='__main__':unittest.main(verbosity=2)
