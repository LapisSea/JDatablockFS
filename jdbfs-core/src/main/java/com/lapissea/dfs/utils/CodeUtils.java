package com.lapissea.dfs.utils;

import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.UtilL;

import java.io.IOException;

public interface CodeUtils{
	
	static void readBytesFromSrc(CodeStream writer, int bytes) throws MalformedJorth{
		var ns = NumberSize.FLAG_INFO.filter(e -> e.bytes == bytes).findFirst().orElseThrow();
		readBytesFromSrc(writer, ns);
	}
	static void readBytesFromSrc(CodeStream writer, NumberSize size) throws MalformedJorth{
		size.readIntConst(writer, "get #arg src", false);
	}
	
	static void rawBitsToValidatedBits(CodeStream writer, int bytes, int bits) throws MalformedJorth{
		//Check integrity bits
		var oneBits = bytes*8 - bits;
		
		if(oneBits == 0){
			//If there is exactly 0 extra bits, then just exit. Nothing to do
			return;
		}
		if(oneBits<0){
			throw new IllegalStateException("More bits than bytes*8");
		}
		
		var checkMask = BitUtils.makeMask(oneBits)<<bits;
		var valueMask = BitUtils.makeMask(bits);
		
		writer.write(
			"""
				static call {} checkFlag start
					dup
					{}
				end
				if not start
					new {} start 'Illegal enum integrity bits' end
					throw
				end
				""", UtilL.class, checkMask, IOException.class
		);
		
		writer.write("{} bit-and", valueMask);
	}
	
}
