package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.FieldIsNull;
import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.fields.BitField;
import com.lapissea.dfs.utils.Iters;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public abstract sealed class BitFieldMerger<T extends IOInstance<T>> extends IOField<T, Object>{
	
	private static final class GeneralMerger<T extends IOInstance<T>> extends BitFieldMerger<T>{
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
			return new GeneralMerger<>(Iters.from(group).<BitField<T, ?>>map(f -> f.maxAsFixedSize(varProvider)).collectToFinalList());
		}
		
	}
	
	private static final class SimpleMerger<T extends IOInstance<T>> extends BitFieldMerger<T>{
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
	}
	
	private static final int[] INTEGRITY_DIVS = IntStream.range(0, 16).map(i -> Math.toIntExact(BitUtils.makeMask(i))).toArray();
	public static void readIntegrityBits(long raw, int totalBits, int readBits) throws IOException{
		readIntegrityBits(raw >>> readBits, raw, totalBits, totalBits - readBits);
	}
	public static void readIntegrityBits(long remainingBits, long raw, int totalBits, int oneBits) throws IOException{
		var integrityDiv = INTEGRITY_DIVS[oneBits];
		var remStored    = integrityDiv - remainingBits;
		var payload      = raw&BitUtils.makeMask(totalBits - oneBits);
		var rem          = payload%integrityDiv;
		if(rem != remStored){
			throw new IOException("Bit integrity failed");
		}
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
		
		if(group.stream().anyMatch(g -> g.getSizeDescriptor().getWordSpace() != WordSpace.BIT)){
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
	
	private final List<ValueGeneratorInfo<T, ?>> generators;
	
	protected final Optional<BitLayout> safetyBits;
	
	private BitFieldMerger(List<BitField<T, ?>> group){
		super(null);
		this.group = List.copyOf(group);
		
		var bits      = IOFieldTools.sumVarsIfAll(group, SizeDescriptor::getFixed);
		var fixedSize = BitUtils.bitsToBytes(bits);
		if(fixedSize.isPresent()){
			initSizeDescriptor(SizeDescriptor.Fixed.of(fixedSize.getAsLong()));
			safetyBits = bits.stream().mapToObj(BitLayout::new).findAny();
		}else{
			safetyBits = Optional.empty();
			initSizeDescriptor(SizeDescriptor.Unknown.of(
				IOFieldTools.sumVars(group, SizeDescriptor::getMin),
				IOFieldTools.sumVarsIfAll(group, SizeDescriptor::getMax),
				(ioPool, prov, inst) -> BitUtils.bitsToBytes(IOFieldTools.sumVars(group, s -> s.calcUnknown(ioPool, prov, inst, WordSpace.BIT)))
			));
		}
		initLateData(FieldSet.of(group.stream().flatMap(IOField::dependencyStream)));
		generators = IOFieldTools.fieldsToGenerators(group);
	}
	
	@Override
	public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
		var res = group.stream().map(field -> {
			Optional<String> str;
			try{
				str = field.instanceToString(ioPool, instance, doShort || TextUtil.USE_SHORT_IN_COLLECTIONS);
			}catch(FieldIsNull e){
				str = Optional.of("<UNINITIALIZED>");
			}
			return str.map(s -> field.getName() + "=" + s);
		}).flatMap(Optional::stream).collect(Collectors.joining(" + ", "{", "}"));
		
		if(res.length() == 2) return Optional.empty();
		return Optional.of(res);
	}
	
	@Override
	public String toString(){
		return group.stream().map(IOField::getName).collect(Collectors.joining("+"));
	}
	@Override
	public Object get(VarPool<T> ioPool, T instance){
		throw new UnsupportedOperationException();
	}
	@Override
	public void set(VarPool<T> ioPool, T instance, Object value){
		throw new UnsupportedOperationException();
	}
	
	@Override
	public String getName(){
		return group.stream().map(IOField::getName).collect(Collectors.joining(" + "));
	}
	
	@Override
	public Stream<? extends IOField<T, ?>> streamUnpackedFields(){
		return Stream.concat(Stream.of(this), group.stream());
	}
	
	public List<BitField<T, ?>> fieldGroup(){
		return group;
	}
	
	public Optional<BitLayout> getSafetyBits(){
		return safetyBits;
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return generators;
	}
	
}
