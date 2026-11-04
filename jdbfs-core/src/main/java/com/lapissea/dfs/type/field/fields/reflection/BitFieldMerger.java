package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.FieldIsNull;
import com.lapissea.dfs.exceptions.UnsupportedCodeGenType;
import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.SpecializedGenerator;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.fields.BitField;
import com.lapissea.dfs.type.string.Doc;
import com.lapissea.dfs.utils.CodeUtils;
import com.lapissea.iterableplus.IterablePP;
import com.lapissea.iterableplus.Iters;
import com.lapissea.jorth.CodeBlock;
import com.lapissea.jorth.exceptions.MalformedJorth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public abstract sealed class BitFieldMerger<T extends IOInstance<T>> extends IOField<T, Object>{
	
	private static final class GeneralMerger<T extends IOInstance<T>> extends BitFieldMerger<T> implements SpecializedGenerator{
		
		private record ReadPlan(List<ReadStep> steps, int initialBytes, int windowBits, int offset){ }
		
		private sealed interface ReadStep{
			record Read(OnBitSpace<?> generator, int bits, int offset) implements ReadStep{ }
			
			record AppendBytes(int bytes) implements ReadStep{ }
		}
		
		@Override
		public void injectReadField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
			if(safetyBits.isEmpty()){
				throw new UnsupportedCodeGenType("Dynamic merged bits field");
			}
			
			ReadPlan plan = computePlan();
			
			var rawBits = accessMap.temporaryLocalField(long.class, "rawBits", body);
			var field   = accessMap.temporaryLocalField(int.class, "fieldPortion", body);
			
			body.get("src")
			    .call("readWord", List.of(plan.initialBytes()))
			    .set(rawBits);
			
			for(var step : plan.steps()){
				switch(step){
					case ReadStep.AppendBytes(var bytes) -> {
						body.get(rawBits).bitShiftRight(true, Integer.SIZE)
						    .get("src").call("readWord", List.of(bytes))
						    .bitShiftLeft(Integer.SIZE)
						    .bitOr()
						    .set(rawBits);
					}
					case ReadStep.Read(var generator, var bits, var offset) -> {
						body.get(rawBits);
						if(offset>0){
							body.bitShiftRight(true, offset);
						}
						body.cast(int.class)
						    .bitAnd(BitUtils.makeMask(bits))
						    .set(field);
						
						accessMap.markTemporary();
						try{
							generator.injectReadFieldFromBits(body, accessMap, field);
						}finally{
							accessMap.dropTemporary(body);
						}
					}
				}
			}
			
			if(plan.windowBits()>plan.offset()){
				body.get(rawBits);
				CodeUtils.validateOneBits(body, plan.windowBits()/Byte.SIZE, plan.offset());
			}
		}
		
		private ReadPlan computePlan() throws UnsupportedCodeGenType{
			var plan = new ArrayList<ReadStep>(group.size());
			
			long bytes        = getSizeDescriptor().requireFixed(WordSpace.BYTE);
			int  initialBytes = (int)Math.min(Long.BYTES, bytes);
			
			long remainingBytes = bytes - initialBytes;
			int  windowBits     = initialBytes*Byte.SIZE;
			int  offset         = 0;
			
			for(var fi : group){
				var sizeO = fi.getSizeDescriptor().getFixed(WordSpace.BIT);
				if(sizeO.isEmpty()){
					throw new UnsupportedCodeGenType(Log.fmt("Field {}#red has dynamic size", fi));
				}
				long size = sizeO.getAsLong();
				if(size<0 || size>31){
					throw new UnsupportedCodeGenType(Log.fmt("Field {}#red larger than 4 bytes", fi));
				}
				
				if(!(fi instanceof OnBitSpace<?> generator)){
					throw new UnsupportedCodeGenType(Log.fmt("Field {}#red does not support code generation", fi));
				}
				
				int bits = (int)size;
				if(offset + bits>windowBits){
					if(offset<Integer.SIZE || remainingBytes == 0){
						throw new UnsupportedCodeGenType("Bits field cannot fit a rolling window: " + fi);
					}
					
					int appendBytes = (int)Math.min(Integer.BYTES, remainingBytes);
					remainingBytes -= appendBytes;
					offset -= Integer.SIZE;
					windowBits = Integer.SIZE + appendBytes*Byte.SIZE;
					if(offset + bits>windowBits){
						throw new UnsupportedCodeGenType("Bits field exceeds appended window: " + fi);
					}
					plan.add(new ReadStep.AppendBytes(appendBytes));
				}
				
				plan.add(new ReadStep.Read(generator, bits, offset));
				offset += bits;
			}
			
			return new ReadPlan(plan, initialBytes, windowBits, offset);
		}
		
		private GeneralMerger(List<BitField<T, ?>> group){
			super(group);
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			try(var stream = new BitOutputStream(dest)){
				for(var fi : group){
					try{
						if(DEBUG_VALIDATION){
							writeBitsCheckedSize(ioPool, stream, instance, provider, fi);
						}else{
							fi.writeBits(ioPool, stream, instance);
						}
					}catch(Exception e){
						throw new IOException("Failed to write " + fi, e);
					}
				}
			}
		}
		
		private void writeBitsCheckedSize(VarPool<T> ioPool, BitOutputStream stream, T instance, DataProvider provider, BitField<T, ?> fi) throws IOException{
			long size = fi.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT);
			var  oldW = stream.getTotalBits();
			
			fi.writeBits(ioPool, stream, instance);
			
			var written = stream.getTotalBits() - oldW;
			if(written != size) throw new RuntimeException("Written bits " + written + " but " + size + " expected on " + fi);
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			try(var stream = new BitInputStream(src, safetyBits.isPresent()? getSizeDescriptor().requireFixed(WordSpace.BIT) : -1)){
				for(var fi : group){
					if(DEBUG_VALIDATION){
						readBitsCheckedSize(ioPool, stream, instance, provider, fi);
					}else{
						fi.readBits(ioPool, stream, instance);
					}
				}
			}
		}
		
		private void readBitsCheckedSize(VarPool<T> ioPool, BitInputStream stream, T instance, DataProvider provider, BitField<T, ?> fi) throws IOException{
			long size = fi.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT);
			var  oldW = stream.getTotalBits();
			
			fi.readBits(ioPool, stream, instance);
			
			var read = stream.getTotalBits() - oldW;
			if(read != size) throw new RuntimeException("Read bits " + read + " but " + size + " expected on " + fi);
		}
		
		@Override
		public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			if(src.optionallySkipExact(getSizeDescriptor().getFixed(WordSpace.BYTE))){
				return;
			}
			
			try(var stream = new BitInputStream(src, getSizeDescriptor().getMin(WordSpace.BIT))){
				for(var fi : group){
					if(DEBUG_VALIDATION){
						skipBitsCheckedSize(ioPool, provider, instance, stream, fi);
					}else{
						fi.skipReadBits(stream, instance);
					}
				}
			}
		}
		
		private void skipBitsCheckedSize(VarPool<T> ioPool, DataProvider provider, T instance, BitInputStream stream, BitField<T, ?> fi) throws IOException{
			long size = fi.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT);
			var  oldW = stream.getTotalBits();
			
			fi.skipReadBits(stream, instance);
			
			var read = stream.getTotalBits() - oldW;
			if(read != size) throw new RuntimeException("Read bits " + read + " but " + size + " expected on " + fi);
		}
		
		@Override
		public IOField<T, Object> maxAsFixedSize(VaryingSize.Provider varProvider){
			return new GeneralMerger<>(Iters.from(group).toList(f -> f.maxAsFixedSize(varProvider)));
		}
		
	}
	
	private static final class SimpleMerger<T extends IOInstance<T>> extends BitFieldMerger<T> implements SpecializedGenerator{
		@Override
		public void injectSkipField(CodeBlock body, AccessMap accessMap) throws MalformedJorth{
			body.get("src").call("skipExact", args -> args.val(bytes));
		}
		@Override
		public void injectWriteField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
			accessMap.markTemporary();
			var raw = accessMap.temporaryLocalField(long.class, "writeMergedBits", body);
			body.set(raw, 0L);
			int offset = 0;
			for(var field : group){
				if(!(field instanceof SpecializedGenerator.OnBitSpace<?> generator)){
					throw new UnsupportedCodeGenType("Unsupported merged bit writer: " + field);
				}
				body.get(raw);
				generator.injectWriteFieldToBits(body, accessMap);
				if(offset != 0) body.bitShiftLeft(offset);
				body.bitOr().set(raw);
				offset += Math.toIntExact(field.getSizeDescriptor().requireFixed(WordSpace.BIT));
			}
			body.get("dest").get(raw);
			if(oneBits>1){
				int usedBits = offset;
				body.call(CodeUtils.class, "writeIntegrityBits", args -> args.get(raw).val(oneBits).val(usedBits)).bitOr();
			}else if(oneBits == 1){
				body.val(1L<<offset).bitOr();
			}
			body.val(numSize.bytes).call("writeWord", 2);
			accessMap.dropTemporary(body);
		}
		
		private final long       bytes;
		private final int        oneBits;
		private final NumberSize numSize;
		
		private SimpleMerger(List<BitField<T, ?>> group, NumberSize numSize){
			super(group);
			this.numSize = numSize;
			bytes = ((SizeDescriptor.Fixed<T>)getSizeDescriptor()).get(WordSpace.BYTE);
			oneBits = (int)(bytes*Byte.SIZE - IOFieldTools.sumVars(group, s -> s.requireFixed(WordSpace.BIT)));
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var writer = new FlagWriter(numSize);
			for(var fi : group){
				fi.writeBits(ioPool, writer, instance);
			}
			if(oneBits>1){
				var remRaw = calcIntegrityBits(writer.getBuffer(), oneBits);
				writer.writeBits(remRaw, oneBits);
			}else{
				writer.fillNOne(oneBits);
			}
			writer.export(dest);
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var raw    = numSize.read(src);
			var reader = new FlagReader(raw, numSize);
			for(var fi : group){
				fi.readBits(ioPool, reader, instance);
			}
			if(oneBits>1){
				var remRaw = reader.readBits(oneBits);
				readIntegrityBits(remRaw, raw, numSize.bits(), oneBits);
			}else{
				reader.checkNOneAndThrow(oneBits);
			}
		}
		
		@Override
		public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			src.skipExact(bytes);
		}
		@Override
		public void injectReadField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
			var rawBits = accessMap.temporaryLocalField(long.class, "rawBits", body);
			
			int totalBits = 0;
			for(var fi : group){
				if(!(fi instanceof SpecializedGenerator.OnBitSpace<?>)){
					throw new UnsupportedOperationException("Can not generate merged field because a field is not supported:\n  " + fi);
				}
				totalBits += Math.toIntExact(fi.getSizeDescriptor().requireFixed(WordSpace.BIT));
			}
			
			CodeUtils.readBytesFromSrc(body, numSize);
			if(numSize.bits() != totalBits){
				var tb = totalBits;
				body.call(CodeUtils.class, "readIntegrityBits", args -> args.dup().val(numSize.bits()).val(tb));
			}
			
			body.set(rawBits);
			
			var field = accessMap.temporaryLocalField(int.class, "fieldPortion", body);
			
			int bitOffset = 0;
			
			for(var fi : group){
				int bits = Math.toIntExact(fi.getSizeDescriptor().requireFixed(WordSpace.BIT));
				if(bits>31){
					throw new UnsupportedCodeGenType("Long bits field");
				}
				var mask = BitUtils.makeMask(bits);
				
				body.get(rawBits);
				if(bitOffset>0){
					body.bitShiftRight(true, bitOffset);
				}
				body.cast(int.class)
				    .bitAnd(mask)
				    .set(field);
				
				bitOffset += bits;
				
				accessMap.markTemporary();
				try{
					((SpecializedGenerator.OnBitSpace<?>)fi).injectReadFieldFromBits(body, accessMap, field);
				}finally{
					accessMap.dropTemporary(body);
				}
			}
		}
	}
	
	private static final int[] INTEGRITY_DIVS = Iters.range(0, 16).mapExact(BitUtils::makeMask).toArray();
	
	public static void readIntegrityBits(long raw, int totalBits, int readBits) throws IOException{
		if(!areIntegrityBitsValid(raw >>> readBits, raw, totalBits, totalBits - readBits)){
			throw new IOException("Bit integrity failed");
		}
	}
	public static void readIntegrityBits(long remainingBits, long raw, int totalBits, int oneBits) throws IOException{
		if(!areIntegrityBitsValid(remainingBits, raw, totalBits, oneBits)){
			throw new IOException("Bit integrity failed");
		}
	}
	public static boolean areIntegrityBitsValid(long raw, int totalBits, int readBits){
		return areIntegrityBitsValid(raw >>> readBits, raw, totalBits, totalBits - readBits);
	}
	public static boolean areIntegrityBitsValid(long remainingBits, long raw, int totalBits, int oneBits){
		var integrityDiv = INTEGRITY_DIVS[oneBits];
		var remStored    = integrityDiv - remainingBits;
		var payload      = raw&BitUtils.makeMask(totalBits - oneBits);
		var rem          = payload%integrityDiv;
		return rem == remStored;
	}
	public static long calcIntegrityBits(long writtenData, int oneBits, int writtenDataBits){
		return calcIntegrityBits(writtenData, oneBits)<<writtenDataBits;
	}
	public static long calcIntegrityBits(long writtenData, int oneBits){
		var integrityDiv = INTEGRITY_DIVS[oneBits];
		var rem          = writtenData%integrityDiv;
		return integrityDiv - rem;
	}
	
	public static <T extends IOInstance<T>> BitFieldMerger<T> of(List<BitField<T, ?>> group){
		if(group.isEmpty()) throw new IllegalArgumentException("group is empty");
		
		if(Iters.from(group).anyMatch(g -> g.getSizeDescriptor().getWordSpace() != WordSpace.BIT)){
			throw new IllegalArgumentException(group + "");
		}
		group = List.copyOf(group);
		
		
		var oBits = IOFieldTools.sumVarsIfAll(group, g -> g.getFixed(WordSpace.BIT));
		simple:
		if(oBits.isPresent()){
			var bits = oBits.getAsLong();
			if(bits>=63) break simple;
			var bytes   = BitUtils.bitsToBytes((int)bits);
			var numSize = NumberSize.byBytes(bytes);
			if(numSize.bytes != bytes) break simple;
			
			return new SimpleMerger<>(group, numSize);
		}
		return new GeneralMerger<>(group);
	}
	
	public record BitLayout(long usedBits, int safetyBits){
		BitLayout(long bits){
			this(bits, (int)(BitUtils.bitsToBytes(bits)*8 - bits));
		}
		@Override
		public String toString(){
			return usedBits + " + " + safetyBits;
		}
	}
	
	protected final List<BitField<T, ?>> group;
	
	protected final Optional<BitLayout> safetyBits;
	
	private BitFieldMerger(List<BitField<T, ?>> group){
		super(null);
		this.group = List.copyOf(group);
		
		var bits      = IOFieldTools.sumVarsIfAll(group, SizeDescriptor::getFixed);
		var fixedSize = BitUtils.bitsToBytes(bits);
		if(fixedSize.isPresent()){
			initSizeDescriptor(SizeDescriptor.Fixed.of(fixedSize.getAsLong()));
			safetyBits = Iters.ofPresent(bits).mapToObj(BitLayout::new).findFirst();
		}else{
			safetyBits = Optional.empty();
			initSizeDescriptor(SizeDescriptor.Unknown.of(
				IOFieldTools.sumVars(group, SizeDescriptor::getMin),
				IOFieldTools.sumVarsIfAll(group, SizeDescriptor::getMax),
				(ioPool, prov, inst) -> BitUtils.bitsToBytes(IOFieldTools.sumVars(group, s -> s.calcUnknown(ioPool, prov, inst, WordSpace.BIT)))
			));
		}
		initLateData(FieldSet.of(Iters.from(group).flatMap(IOField::getDependencies)));
	}
	
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of();
	}
	
	@Override
	public Optional<Doc> instanceToDoc(VarPool<T> ioPool, T instance, boolean doShort){
		var entries = new ArrayList<Doc>();
		for(var field : group){
			Optional<Doc> doc;
			try{
				doc = field.instanceToDoc(ioPool, instance, doShort);
			}catch(FieldIsNull e){
				doc = Optional.of(Doc.text(IOFieldTools.UNINITIALIZED_FIELD_SIGN));
			}
			var name = field.getName();
			doc.ifPresent(d -> entries.add(Doc.concat(Doc.text(name + "="), d)));
		}
		if(entries.isEmpty()) return Optional.empty();
		return Optional.of(Doc.group(Doc.concat(
			Doc.text("{"),
			Doc.nest(Doc.concat(Doc.softLine(), Doc.join(Doc.concat(Doc.text(" +"), Doc.line()), entries))),
			Doc.softLine(),
			Doc.text("}")
		)));
	}
	
	@Override
	public String toString(){ return getName(); }
	@Override
	public Object get(VarPool<T> ioPool, T instance){
		throw new UnsupportedOperationException();
	}
	@Override
	public void set(VarPool<T> ioPool, T instance, Object value){
		throw new UnsupportedOperationException();
	}
	
	private String name;
	@Override
	public String getName(){
		var n = name;
		if(n == null) n = name = Iters.from(group).joinAsStr(" + ", IOField::getName);
		return n;
	}
	
	@Override
	public IterablePP<IOField<T, ?>> iterUnpackedFields(){
		return Iters.concat1N(this, group);
	}
	
	public List<BitField<T, ?>> fieldGroup(){
		return group;
	}
	
	@Override
	public boolean needsIOPool(){
		return Iters.from(group).anyMatch(IOField::needsIOPool);
	}

	public Optional<BitLayout> getSafetyBits(){
		return safetyBits;
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return Iters.from(group).flatMap(IOField::getGenerators).toModList();
	}
	
}
