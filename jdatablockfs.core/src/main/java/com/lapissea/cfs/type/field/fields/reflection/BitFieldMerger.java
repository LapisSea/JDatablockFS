package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
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
	
	private final List<IOField.Bit<T, ?>> group;
	
	private final List<ValueGeneratorInfo<T, ?>> generators;
	
	private final SizeDescriptor<T> sizeDescriptor;
	
	private final Optional<BitLayout> safetyBits;
	
	public BitFieldMerger(List<IOField.Bit<T, ?>> group){
		super(null);
		assert !group.isEmpty();
		
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
	public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		try(var stream=new BitOutputStream(dest)){
			for(var fi : group){
				if(DEBUG_VALIDATION){
					long size=fi.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT);
					var  oldW=stream.getTotalBits();
					
					try{
						fi.writeBits(ioPool, stream, instance);
					}catch(Exception e){
						throw new IOException("Failed to write "+fi, e);
					}
					var written=stream.getTotalBits()-oldW;
					if(written!=size) throw new RuntimeException("Written bits "+written+" but "+size+" expected on "+fi);
				}else{
					try{
						fi.writeBits(ioPool, stream, instance);
					}catch(Exception e){
						throw new IOException("Failed to write "+fi, e);
					}
				}
			}
		}
	}
	
	@Override
	public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try(var stream=new BitInputStream(src, safetyBits.isPresent()?getSizeDescriptor().requireFixed(WordSpace.BIT):-1)){
			for(var fi : group){
				if(DEBUG_VALIDATION){
					long size=fi.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT);
					var  oldW=stream.getTotalBits();
					
					fi.readBits(ioPool, stream, instance);
					var read=stream.getTotalBits()-oldW;
					if(read!=size) throw new RuntimeException("Read bits "+read+" but "+size+" expected on "+fi);
				}else{
					fi.readBits(ioPool, stream, instance);
				}
			}
		}
	}
	
	@Override
	public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(src.optionallySkipExact(getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		
		try(var stream=new BitInputStream(src, getSizeDescriptor().getMin(WordSpace.BIT))){
			for(var fi : group){
				if(DEBUG_VALIDATION){
					long size=fi.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT);
					var  oldW=stream.getTotalBits();
					
					fi.skipReadBits(stream, instance);
					var read=stream.getTotalBits()-oldW;
					if(read!=size) throw new RuntimeException("Read bits "+read+" but "+size+" expected on "+fi);
				}else{
					fi.skipReadBits(stream, instance);
				}
			}
		}
	}
	
	@Override
	public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort){
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
	public Object get(Struct.Pool<T> ioPool, T instance){
		throw new UnsupportedOperationException();
	}
	@Override
	public void set(Struct.Pool<T> ioPool, T instance, Object value){
		throw new UnsupportedOperationException();
	}
	
	@Override
	public String getName(){
		return group.stream().map(IOField::getName).collect(Collectors.joining(" + "));
	}
	
	@Override
	public IOField<T, Object> implMaxAsFixedSize(){
		return new BitFieldMerger<>(group.stream().<Bit<T, ?>>map(Bit::implMaxAsFixedSize).toList());
	}
	
	@Override
	public Stream<? extends IOField<T, ?>> streamUnpackedFields(){
		return group.stream();
	}
	
	public List<Bit<T, ?>> fieldGroup(){
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
