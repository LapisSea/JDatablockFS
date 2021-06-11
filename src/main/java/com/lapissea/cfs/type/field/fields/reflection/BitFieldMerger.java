package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.*;

public class BitFieldMerger<T extends IOInstance<T>> extends IOField<T, Object>{
	
	private final List<IOField.Bit<T, ?>> group;
	
	private final SizeDescriptor<T> sizeDescriptor;
	
	
	public BitFieldMerger(List<IOField.Bit<T, ?>> group){
		super(null);
		assert !group.isEmpty();
		this.group=List.copyOf(group);
		
		var fixedSize=Utils.bitToByte(IOFieldTools.sumVarsIfAll(group, SizeDescriptor::fixed));
		if(fixedSize.isPresent()) sizeDescriptor=new SizeDescriptor.Fixed<>(WordSpace.BYTE, fixedSize.getAsLong());
		else sizeDescriptor=new SizeDescriptor.Unknown<>(WordSpace.BYTE, IOFieldTools.sumVars(group, SizeDescriptor::min), IOFieldTools.sumVarsIfAll(group, SizeDescriptor::max)){
			@Override
			public long variable(T instance){
				return Utils.bitToByte(group.stream().mapToLong(s->s.getSizeDescriptor().variable(instance)).sum());
			}
		};
		initCommon(group.stream().flatMap(f->f.getDependencies().stream()).distinct().toList());
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescriptor;
	}
	
	@Override
	public void write(ContentWriter dest, T instance) throws IOException{
		
		try(var stream=new BitOutputStream(dest)){
			for(var fi : group){
				if(DEBUG_VALIDATION){
					var  sizeD=fi.getSizeDescriptor();
					long size;
					var  fixed=sizeD.fixed();
					if(fixed.isPresent()) size=fixed.getAsLong();
					else size=sizeD.variable(instance);
					var oldW=stream.getTotalBits();
					
					try{
						fi.writeBits(stream, instance);
					}catch(Exception e){
						throw new IOException("Failed to write "+TextUtil.toShortString(fi), e);
					}
					var written=stream.getTotalBits()-oldW;
					if(written!=size) throw new RuntimeException("Written bits "+written+" but "+size+" expected on "+fi);
				}else{
					try{
						fi.writeBits(stream, instance);
					}catch(Exception e){
						throw new IOException("Failed to write "+TextUtil.toShortString(fi), e);
					}
				}
			}
		}
	}
	
	@Override
	public void read(ContentReader src, T instance) throws IOException{
		try(var stream=new BitInputStream(src)){
			for(var fi : group){
				if(DEBUG_VALIDATION){
					var  sizeD=fi.getSizeDescriptor();
					long size;
					var  fixed=sizeD.fixed();
					if(fixed.isPresent()) size=fixed.getAsLong();
					else size=sizeD.variable(instance);
					var oldW=stream.getTotalBits();
					
					fi.readBits(stream, instance);
					var read=stream.getTotalBits()-oldW;
					if(read!=size) throw new RuntimeException("Read bits "+read+" but "+size+" expected on "+fi);
				}else{
					fi.readBits(stream, instance);
				}
			}
		}
	}
	
	@Override
	public Object get(T instance){
		throw new UnsupportedOperationException();
	}
	@Override
	public void set(T instance, Object value){
		throw new UnsupportedOperationException();
	}
	
	@Override
	public String getName(){
		return group.stream().map(IOField::getName).collect(Collectors.joining(" + "));
	}
}
