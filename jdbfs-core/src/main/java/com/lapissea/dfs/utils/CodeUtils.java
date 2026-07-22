package com.lapissea.dfs.utils;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.dfs.type.field.fields.reflection.DynamicSupport;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.redo.CodeBlock;
import com.lapissea.util.UtilL;

import java.io.IOException;

public interface CodeUtils{
	
	static Object dynamic_readTyp(IOField<?, ?> caller, int typeID, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		IOType typ = provider.getTypeDb().fromID(typeID);
		return DynamicSupport.readTyp(typ, provider, src, caller.makeContext(genericContext));
	}
	
	static void readIntegrityBits(long raw, int totalBits, int readBits) throws IOException{
		BitFieldMerger.readIntegrityBits(raw, totalBits, readBits);
	}
	
	static void readBytesFromSrc(CodeBlock body, int bytes) throws MalformedJorth{
		var ns = NumberSize.FLAG_INFO.filter(e -> e.bytes == bytes).findFirst().orElseThrow();
		readBytesFromSrc(body, ns);
	}
	static void readBytesFromSrc(CodeBlock body, NumberSize size) throws MalformedJorth{
		size.readConst(body, a -> a.get("src"), false);
	}
	
	static void rawBitsToValidatedBits(CodeBlock body, int bytes, int bits) throws MalformedJorth{
		//Check integrity bits
		var oneBits = bytes*8 - bits;
		
		if(oneBits == 0){
			//If there is exactly 0 extra bits, then just exit. Nothing to do
			return;
		}
		if(oneBits<0){
			throw new IllegalStateException("More bits than bytes*8");
		}
		
		long checkMask = BitUtils.makeMask(oneBits)<<bits;
		long valueMask = BitUtils.makeMask(bits);
		
		body.call(UtilL.class, "checkFlag", args -> args.dup().val(checkMask))
		    .ifFalse(branch -> {
			    branch.newObj(IOException.class, e -> e.val("Illegal enum integrity bits"))
			          .throwOp();
		    })
		    .bitAnd(valueMask);
		
	}
	
}
