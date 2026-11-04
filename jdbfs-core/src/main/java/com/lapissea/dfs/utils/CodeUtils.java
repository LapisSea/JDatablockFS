package com.lapissea.dfs.utils;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.dfs.type.field.fields.reflection.DynamicSupport;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldDynamicReferenceObject;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldUnmanagedObjectReference;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldChunkPointer;
import com.lapissea.jorth.CodeBlock;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.UtilL;

import java.io.IOException;

public interface CodeUtils{
	@SuppressWarnings({"rawtypes", "unchecked"})
	static NumberSize primitive_writeSize(IOField<?, ?> field, VarPool<?> pool, IOInstance<?> instance, NumberSize needed){
		return ((IOFieldPrimitive)field).generatedWriteSize(pool, instance, needed);
	}
	@SuppressWarnings({"rawtypes", "unchecked"})
	static NumberSize primitive_size(IOField<?, ?> field, VarPool<?> pool, IOInstance<?> instance){
		return ((IOFieldPrimitive)field).generatedSize(pool, instance);
	}
	@SuppressWarnings({"rawtypes", "unchecked"})
	static NumberSize pointer_writeSize(IOField<?, ?> field, VarPool<?> pool, IOInstance<?> instance, long value){
		return ((IOFieldChunkPointer)field).generatedWriteSize(pool, instance, value);
	}
	@SuppressWarnings({"rawtypes", "unchecked"})
	static NumberSize pointer_size(IOField<?, ?> field, VarPool<?> pool, IOInstance<?> instance){
		return ((IOFieldChunkPointer)field).generatedSize(pool, instance);
	}
	static long writeIntegrityBits(long raw, int oneBits, int usedBits){
		return BitFieldMerger.calcIntegrityBits(raw, oneBits, usedBits);
	}

	static Object dynamic_readReference(IOField<?, ?> caller, int typeID, Reference reference, DataProvider provider, GenericContext genericContext) throws IOException{
		return ((IOFieldDynamicReferenceObject<?, ?>)caller).readReferencedValue(provider, typeID, reference, genericContext);
	}

	static Object unmanaged_readValue(IOField<?, ?> caller, ChunkPointer pointer, DataProvider provider, GenericContext genericContext) throws IOException{
		if(pointer.isNull()) return null;
		return ((IOFieldUnmanagedObjectReference<?, ?>)caller).makeValueObject(provider, pointer.dereference(provider), genericContext);
	}
	
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
			    branch.throwNew(IOException.class, "Illegal enum integrity bits");
		    })
		    .bitAnd(valueMask);
		
	}
	
	static void validateOneBits(CodeBlock body, int bytes, int bits) throws MalformedJorth{
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
		
		body.val(checkMask);
		body.call(UtilL.class, "checkFlag", 2)
		    .ifFalse(branch -> {
			    branch.throwNew(IOException.class, "Illegal enum integrity bits");
		    });
		
	}
	
}
