package com.lapissea.cfs.type.field.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOField;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.*;

public class BitFieldMerger<T extends IOInstance<T>> extends IOField<T, Object>{
	
	private final List<IOField.Bit<T, ?>> group;
	
	private final OptionalLong fixedSize;
	
	public BitFieldMerger(List<IOField.Bit<T, ?>> group){
		this.group=List.copyOf(group);
		fixedSize=Utils.bitToByte(group.stream().map(IOField.Bit::getFixedSize).reduce(OptionalLong.of(0), Utils::addIfBoth));
		initCommon(group.stream().flatMap(f->f.getDeps().stream()).distinct().toList(), -2);
	}
	
	@Override
	public long calcSize(T instance){
		return Utils.bitToByte(group.stream().mapToLong(s->s.calcSize(instance)).sum());
	}
	@Override
	public OptionalLong getFixedSize(){
		return fixedSize;
	}
	
	@Override
	public void write(ContentWriter dest, T instance) throws IOException{
		
		try(var buff=dest
			             .writeTicket(calcSize(instance))
			             .requireExact()
			             .onFinish((i, b)->LogUtil.println(Utils.byteArrayToBitString(b, i)))
			             .submit();
		    var stream=new BitOutputStream(buff)){
			for(var fi : group){
				if(DEBUG_VALIDATION){
					long size;
					var  fixed=fi.getFixedSize();
					if(fixed.isPresent()) size=fixed.getAsLong();
					else size=fi.calcSize(instance);
					var oldW=stream.getTotalBits();
					
					fi.writeBits(stream, instance);
					var written=stream.getTotalBits()-oldW;
					if(written!=size) throw new RuntimeException("Written bits "+written+" but "+size+" expected on "+fi);
				}else{
					fi.writeBits(stream, instance);
				}
			}
		}
	}
	
	@Override
	public void read(ContentReader src, T instance) throws IOException{
		try(var stream=new BitInputStream(src)){
			for(var fi : group){
				if(DEBUG_VALIDATION){
					long size;
					var  fixed=fi.getFixedSize();
					if(fixed.isPresent()) size=fixed.getAsLong();
					else size=fi.calcSize(instance);
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
	public String toShortString(){
		return toString();
	}
	@Override
	public String toString(){
		return group.stream().map(IOField::getNameOrId).collect(Collectors.joining(" + ", "{", "}"));
	}
}
