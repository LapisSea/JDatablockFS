package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.fields.BitField;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class BitFieldMerger<T extends IOInstance<T>> extends IOField<T, Object>{
	
	public record BitLayout(long usedBits, int safetyBits){
		BitLayout(long bits){
			this(bits, (int)(Utils.bitToByte(bits)*8-bits));
		}
		@Override
		public String toString(){
			return usedBits+" + "+safetyBits;
		}
	}
	
	private final List<BitField<T, ?>> group;
	
	private final List<ValueGeneratorInfo<T, ?>> generators;
	
	private final SizeDescriptor<T> sizeDescriptor;
	
	private final Optional<BitLayout> safetyBits;
	
	public BitFieldMerger(List<BitField<T, ?>> group){
		super(null);
		if(group.isEmpty()) throw new IllegalArgumentException("group is empty");
		
		if(group.stream().anyMatch(g->g.getSizeDescriptor().getWordSpace()!=WordSpace.BIT)){
			throw new IllegalArgumentException(group+"");
		}
		
		this.group=List.copyOf(group);
		
		var bits     =IOFieldTools.sumVarsIfAll(group, SizeDescriptor::getFixed);
		var fixedSize=Utils.bitToByte(bits);
		if(fixedSize.isPresent()){
			sizeDescriptor=SizeDescriptor.Fixed.of(fixedSize.getAsLong());
			safetyBits=bits.stream().mapToObj(BitLayout::new).findAny();
		}else{
			safetyBits=Optional.empty();
			sizeDescriptor=SizeDescriptor.Unknown.of(
				IOFieldTools.sumVars(group, SizeDescriptor::getMin),
				IOFieldTools.sumVarsIfAll(group, SizeDescriptor::getMax),
				(ioPool, prov, inst)->Utils.bitToByte(IOFieldTools.sumVars(group, s->s.calcUnknown(ioPool, prov, inst, WordSpace.BIT)))
			);
		}
		initLateData(FieldSet.of(group.stream().flatMap(IOField::dependencyStream)));
		generators=Utils.nullIfEmpty(streamUnpackedFields().flatMap(IOField::generatorStream).toList());
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescriptor;
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		try(var stream=new BitOutputStream(dest)){
			for(var fi : group){
				try{
					if(DEBUG_VALIDATION){
						writeBitsCheckedSize(ioPool, stream, instance, provider, fi);
					}else{
						fi.writeBits(ioPool, stream, instance);
					}
				}catch(Exception e){
					throw new IOException("Failed to write "+fi, e);
				}
			}
		}
	}
	
	private void writeBitsCheckedSize(VarPool<T> ioPool, BitOutputStream stream, T instance, DataProvider provider, BitField<T, ?> fi) throws IOException{
		long size=fi.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT);
		var  oldW=stream.getTotalBits();
		
		fi.writeBits(ioPool, stream, instance);
		
		var written=stream.getTotalBits()-oldW;
		if(written!=size) throw new RuntimeException("Written bits "+written+" but "+size+" expected on "+fi);
	}
	
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try(var stream=new BitInputStream(src, safetyBits.isPresent()?getSizeDescriptor().requireFixed(WordSpace.BIT):-1)){
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
		long size=fi.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT);
		var  oldW=stream.getTotalBits();
		
		fi.readBits(ioPool, stream, instance);
		
		var read=stream.getTotalBits()-oldW;
		if(read!=size) throw new RuntimeException("Read bits "+read+" but "+size+" expected on "+fi);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(src.optionallySkipExact(getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		
		try(var stream=new BitInputStream(src, getSizeDescriptor().getMin(WordSpace.BIT))){
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
		long size=fi.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT);
		var  oldW=stream.getTotalBits();
		
		fi.skipReadBits(stream, instance);
		
		var read=stream.getTotalBits()-oldW;
		if(read!=size) throw new RuntimeException("Read bits "+read+" but "+size+" expected on "+fi);
	}
	
	@Override
	public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
		var res=group.stream().map(field->{
			Optional<String> str;
			try{
				str=field.instanceToString(ioPool, instance, doShort||TextUtil.USE_SHORT_IN_COLLECTIONS);
			}catch(FieldIsNullException e){
				str=Optional.of("<UNINITIALIZED>");
			}
			return str.map(s->field.getName()+"="+s);
		}).filter(Optional::isPresent).map(Optional::get).collect(Collectors.joining(" + ", "{", "}"));
		
		if(res.length()==2) return Optional.empty();
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
	public IOField<T, Object> implMaxAsFixedSize(){
		return new BitFieldMerger<>(group.stream().<BitField<T, ?>>map(BitField::implMaxAsFixedSize).toList());
	}
	
	@Override
	public Stream<? extends IOField<T, ?>> streamUnpackedFields(){
		return group.stream();
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
