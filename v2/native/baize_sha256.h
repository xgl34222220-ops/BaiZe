#ifndef BAIZE_SHA256_H
#define BAIZE_SHA256_H
/* BaiZe's small, self-contained SHA-256 implementation of FIPS 180-4 §§4-6.
 * Algorithm/constant reference: https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf
 * Original repository code, not copied from a third-party crypto library.
 * This file makes no claim of FIPS implementation validation/certification.
 */
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
typedef struct { uint32_t h[8]; uint64_t bytes; unsigned char block[64]; size_t used; } BaizeSha256;
static uint32_t bz_rotr(uint32_t x, unsigned n) { return (x >> n) | (x << (32U - n)); }
static void bz_sha_block(BaizeSha256 *ctx, const unsigned char *p) {
    static const uint32_t k[64] = {
        0x428a2f98U,0x71374491U,0xb5c0fbcfU,0xe9b5dba5U,0x3956c25bU,0x59f111f1U,0x923f82a4U,0xab1c5ed5U,
        0xd807aa98U,0x12835b01U,0x243185beU,0x550c7dc3U,0x72be5d74U,0x80deb1feU,0x9bdc06a7U,0xc19bf174U,
        0xe49b69c1U,0xefbe4786U,0x0fc19dc6U,0x240ca1ccU,0x2de92c6fU,0x4a7484aaU,0x5cb0a9dcU,0x76f988daU,
        0x983e5152U,0xa831c66dU,0xb00327c8U,0xbf597fc7U,0xc6e00bf3U,0xd5a79147U,0x06ca6351U,0x14292967U,
        0x27b70a85U,0x2e1b2138U,0x4d2c6dfcU,0x53380d13U,0x650a7354U,0x766a0abbU,0x81c2c92eU,0x92722c85U,
        0xa2bfe8a1U,0xa81a664bU,0xc24b8b70U,0xc76c51a3U,0xd192e819U,0xd6990624U,0xf40e3585U,0x106aa070U,
        0x19a4c116U,0x1e376c08U,0x2748774cU,0x34b0bcb5U,0x391c0cb3U,0x4ed8aa4aU,0x5b9cca4fU,0x682e6ff3U,
        0x748f82eeU,0x78a5636fU,0x84c87814U,0x8cc70208U,0x90befffaU,0xa4506cebU,0xbef9a3f7U,0xc67178f2U
    };
    uint32_t w[64];
    for (size_t i=0;i<16;i++) w[i]=((uint32_t)p[4*i]<<24)|((uint32_t)p[4*i+1]<<16)|((uint32_t)p[4*i+2]<<8)|p[4*i+3];
    for (size_t i=16;i<64;i++) {
        uint32_t a=w[i-15],b=w[i-2];
        w[i]=w[i-16]+(bz_rotr(a,7)^bz_rotr(a,18)^(a>>3))+w[i-7]+(bz_rotr(b,17)^bz_rotr(b,19)^(b>>10));
    }
    uint32_t a=ctx->h[0],b=ctx->h[1],c=ctx->h[2],d=ctx->h[3],e=ctx->h[4],f=ctx->h[5],g=ctx->h[6],h=ctx->h[7];
    for(size_t i=0;i<64;i++) {
        uint32_t t1=h+(bz_rotr(e,6)^bz_rotr(e,11)^bz_rotr(e,25))+((e&f)^((~e)&g))+k[i]+w[i];
        uint32_t t2=(bz_rotr(a,2)^bz_rotr(a,13)^bz_rotr(a,22))+((a&b)^(a&c)^(b&c));
        h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
    }
    ctx->h[0]+=a;ctx->h[1]+=b;ctx->h[2]+=c;ctx->h[3]+=d;ctx->h[4]+=e;ctx->h[5]+=f;ctx->h[6]+=g;ctx->h[7]+=h;
}
static void bz_sha_init(BaizeSha256 *ctx) {
    static const uint32_t h[8]={0x6a09e667U,0xbb67ae85U,0x3c6ef372U,0xa54ff53aU,0x510e527fU,0x9b05688cU,0x1f83d9abU,0x5be0cd19U};
    memcpy(ctx->h,h,sizeof(h));ctx->bytes=0;ctx->used=0;
}
static void bz_sha_update(BaizeSha256 *ctx,const unsigned char *data,size_t length) {
    ctx->bytes+=(uint64_t)length;
    while(length) {
        if(!ctx->used && length>=64) { bz_sha_block(ctx,data);data+=64;length-=64;continue; }
        size_t take=64-ctx->used;if(take>length)take=length;
        memcpy(ctx->block+ctx->used,data,take);ctx->used+=take;data+=take;length-=take;
        if(ctx->used==64){bz_sha_block(ctx,ctx->block);ctx->used=0;}
    }
}
static void bz_sha_final(BaizeSha256 *ctx,char output[65]) {
    uint64_t bits=ctx->bytes*8U;unsigned char padding[128]={0x80};
    size_t count=ctx->used<56?56-ctx->used:120-ctx->used;
    for(size_t i=0;i<8;i++)padding[count+i]=(unsigned char)(bits>>(56-8*i));
    bz_sha_update(ctx,padding,count+8);
    static const char hex[]="0123456789abcdef";
    for(size_t i=0;i<32;i++){unsigned char b=(unsigned char)(ctx->h[i/4]>>(24-8*(i%4)));output[2*i]=hex[b>>4];output[2*i+1]=hex[b&15];}
    output[64]='\0';
}
static int baize_sha256_hex_valid(const char *text) {
    if(!text || strlen(text)!=64)return 0;
    for(size_t i=0;i<64;i++)if(!((text[i]>='0'&&text[i]<='9')||(text[i]>='a'&&text[i]<='f')))return 0;
    return 1;
}
/* Reads exactly the authorized length; growth, short reads, errors, cancellation
 * and time-budget expiry return without a digest. Caller owns before/after fstat
 * and keeps this exact anchored fd open through the final identity check. */
static int baize_sha256_fd(int fd,uint64_t expected,char output[65],int (*abort_check)(void *),void *context) {
    BaizeSha256 sha;bz_sha_init(&sha);unsigned char data[65536];uint64_t total=0;
    for(;;) {
        int code=abort_check?abort_check(context):0;if(code)return code;
        size_t limit=sizeof(data);if(expected-total<sizeof(data))limit=(size_t)(expected-total)+1;
        ssize_t got=read(fd,data,limit);
        if(got<0){if(errno==EINTR)continue;return 8;}
        if(!got)break;
        if((uint64_t)got>expected-total)return 8;
        bz_sha_update(&sha,data,(size_t)got);total+=(uint64_t)got;
    }
    if(total!=expected)return 8;
    bz_sha_final(&sha,output);return 0;
}
#endif
