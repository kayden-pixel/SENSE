#!/usr/bin/env python3
"""Inspect ELF PT_LOAD headers and ZIP payload placement for every packaged .so."""
import argparse, hashlib, json, re, struct, sys, zipfile
PAGE=16384
ABIS={'arm64-v8a':(2,183),'x86_64':(2,62),'armeabi-v7a':(1,40),'x86':(1,3)}

def inspect_elf(data, abi):
    errors, segments=[],[]
    try:
        if len(data)<16 or data[:4]!=bytes([127,69,76,70]): raise ValueError('invalid ELF magic')
        cls, encoding, version=data[4:7]
        if cls not in (1,2) or encoding!=1 or version!=1: raise ValueError('invalid ELF class/encoding/version')
        header_size=64 if cls==2 else 52
        if len(data)<header_size: raise ValueError('truncated ELF header')
        kind,machine=struct.unpack_from('<HH',data,16)
        if kind!=3 or ABIS.get(abi)!=(cls,machine): raise ValueError('not an ET_DYN shared object for the declared ABI')
        phoff=struct.unpack_from('<Q' if cls==2 else '<I',data,32 if cls==2 else 28)[0]
        ehsize,phsize,phnum=struct.unpack_from('<HHH',data,52 if cls==2 else 40)
        if ehsize!=header_size or phsize!=(56 if cls==2 else 32) or phnum in (0,65535): raise ValueError('invalid ELF/program header sizes/count')
        if phoff<header_size or phoff+phnum*phsize>len(data): raise ValueError('program header table outside file')
        for i in range(phnum):
            fields=struct.unpack_from('<IIQQQQQQ' if cls==2 else '<IIIIIIII',data,phoff+i*phsize)
            if fields[0]!=1: continue
            if cls==2: _,flags,offset,address,_,filesz,memsz,align=fields
            else: _,offset,address,_,filesz,memsz,flags,align=fields
            segments.append(dict(offset=offset,vaddr=address,filesz=filesz,memsz=memsz,align=align,flags=flags))
            if align<PAGE or align&(align-1): errors.append(f'PT_LOAD[{i}] p_align={align} is not a power of two >= {PAGE}')
            if (address-offset)%PAGE or (align and (address-offset)%align): errors.append(f'PT_LOAD[{i}] virtual/file offsets are not congruent')
            if filesz>memsz or offset+filesz>len(data): errors.append(f'PT_LOAD[{i}] segment outside file/memory')
        if not segments: errors.append('no PT_LOAD segments')
    except (ValueError,struct.error,IndexError) as exc: errors.append(str(exc))
    return errors,segments

def audit(apk):
    entries,errors,names=[],[],set()
    with zipfile.ZipFile(apk) as archive,open(apk,'rb') as raw:
        for entry in archive.infolist():
            if not entry.filename.endswith('.so'): continue
            if entry.filename in names: errors.append(entry.filename+': duplicate library entry')
            names.add(entry.filename)
            parts=entry.filename.split('/')
            abi=parts[1] if len(parts)==3 and parts[0]=='lib' else 'unknown'
            data=archive.read(entry)
            problems,segments=inspect_elf(data,abi)
            raw.seek(entry.header_offset); header=raw.read(30); payload=None
            if len(header)!=30 or header[:4]!=bytes([80,75,3,4]): problems.append('invalid local ZIP header')
            else:
                name_len,extra_len=struct.unpack_from('<HH',header,26)
                payload=entry.header_offset+30+name_len+extra_len
                if entry.compress_type==zipfile.ZIP_STORED and payload%PAGE: problems.append('uncompressed ZIP payload is not 16 KB aligned')
            entries.append(dict(path=entry.filename,abi=abi,sha256=hashlib.sha256(data).hexdigest(),zipPayloadOffset=payload,loadSegments=segments,errors=problems))
            errors.extend(entry.filename+': '+issue for issue in problems)
    if not entries: errors.append('no native shared libraries found')
    digest=hashlib.sha256()
    with open(apk,"rb") as source:
        for chunk in iter(lambda:source.read(1048576),b""): digest.update(chunk)
    return dict(apk=str(apk),apkSha256=digest.hexdigest(),libraries=entries,errors=errors,passed=not errors)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('apk');parser.add_argument('--json',dest='json_path');args=parser.parse_args()
    try: report=audit(args.apk)
    except (OSError,zipfile.BadZipFile,RuntimeError) as exc: print('Audit failed:',exc);return 1
    for entry in report['libraries']: print(entry['path']+': '+('FAIL' if entry['errors'] else 'PASS')+' PT_LOAD p_align='+','.join(str(s['align']) for s in entry['loadSegments']))
    print('Checked',len(report['libraries']),'native libraries;',len(report['errors']),'errors')
    for error in report['errors']: print(error)
    if args.json_path:
        with open(args.json_path,'w',encoding='utf-8') as out: json.dump(report,out,indent=2)
    return 0 if report['passed'] else 1

if __name__=='__main__': sys.exit(main())
