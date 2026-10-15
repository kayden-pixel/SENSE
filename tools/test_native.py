import struct,unittest,tempfile,zipfile
from pathlib import Path
from check_native import inspect_elf,audit

def elf(align=16384,offset=0,address=0,filesz=128,memsz=128):
    data=bytearray(128);data[:7]=bytes([127,69,76,70,2,1,1]);struct.pack_into('<HHI',data,16,3,183,1);struct.pack_into('<Q',data,32,64);struct.pack_into('<HHH',data,52,64,56,1);struct.pack_into('<IIQQQQQQ',data,64,1,5,offset,address,0,filesz,memsz,align);return bytes(data)
class NativeAuditTest(unittest.TestCase):
    def test_valid_elf(self): self.assertEqual(inspect_elf(elf(),'arm64-v8a')[0],[])
    def test_bad_alignment_even_when_zip_compressed(self):
        with tempfile.TemporaryDirectory() as folder:
            apk=Path(folder)/'test.apk'
            with zipfile.ZipFile(apk,'w',compression=zipfile.ZIP_DEFLATED) as archive: archive.writestr('lib/arm64-v8a/libbad.so',elf(align=4096))
            self.assertFalse(audit(apk)['passed'])
    def test_non_power_of_two_and_offset_mismatch(self):
        for data in (elf(align=24576),elf(address=4096)): self.assertTrue(inspect_elf(data,'arm64-v8a')[0])
    def test_segment_sizes_and_abi(self):
        self.assertTrue(inspect_elf(elf(filesz=129),'arm64-v8a')[0]);self.assertTrue(inspect_elf(elf(memsz=127),'arm64-v8a')[0]);self.assertTrue(inspect_elf(elf(),'x86_64')[0])
    def test_truncation_and_missing_loads(self):
        for data in (b'',elf()[:60]): self.assertTrue(inspect_elf(data,'arm64-v8a')[0])
        data=bytearray(elf());struct.pack_into('<I',data,64,4);self.assertTrue(inspect_elf(data,'arm64-v8a')[0])
if __name__=='__main__': unittest.main()
