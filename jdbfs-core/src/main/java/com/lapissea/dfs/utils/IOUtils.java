package com.lapissea.dfs.utils;

import java.io.IOException;

import static com.lapissea.dfs.config.GlobalConfig.BATCH_BYTES;

public class IOUtils{
	
	public interface ConsumerBaII{
		void accept(byte[] bytes, int off, int len) throws IOException;
	}
	
	public static int floatToShortBits(float fval){
		int fbits = Float.floatToIntBits(fval);
		int sign  = fbits >>> 16&0x8000;
		int val   = (fbits&0x7fffffff) + 0x1000;
		
		if(val>=0x47800000){
			if((fbits&0x7fffffff)>=0x47800000){
				if(val<0x7f800000) return sign|0x7c00;
				return sign|0x7c00|(fbits&0x007fffff) >>> 13;
			}
			return sign|0x7bff;
		}
		if(val>=0x38800000) return sign|val - 0x38000000 >>> 13;
		if(val<0x33000000) return sign;
		val = (fbits&0x7fffffff) >>> 23;
		return sign|((fbits&0x7fffff|0x800000) + (0x800000 >>> val - 102) >>> 126 - val);
	}
	public static float shortBitsToFloat(int hbits){
		int mant = hbits&0x03ff;
		int exp  = hbits&0x7c00;
		if(exp == 0x7c00) exp = 0x3fc00;
		else if(exp != 0){
			exp += 0x1c000;
			if(mant == 0 && exp>0x1c400) return Float.intBitsToFloat((hbits&0x8000)<<16|exp<<13|0x3ff);
		}else if(mant != 0){
			exp = 0x1c400;
			do{
				mant <<= 1;
				exp -= 0x400;
			}while((mant&0x400) == 0);
			mant &= 0x3ff;
		}
		return Float.intBitsToFloat((hbits&0x8000)<<16|(exp|mant)<<13);
	}
	
	public static void zeroFill(ConsumerBaII dest, long size) throws IOException{
		var  part = new byte[(int)Math.min(size, BATCH_BYTES)];
		long left = size;
		while(left>0){
			int write = (int)Math.min(left, part.length);
			dest.accept(part, 0, write);
			left -= write;
		}
	}
}
