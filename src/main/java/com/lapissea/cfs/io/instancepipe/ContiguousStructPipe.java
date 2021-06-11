package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.List;

import static com.lapissea.cfs.GlobalConfig.*;

public class ContiguousStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Struct<T> struct){
		return of(ContiguousStructPipe.class, struct);
	}
	
	private final List<IOField<T, ?>> ioFields;
	
	public ContiguousStructPipe(Struct<T> type){
		super(type);
		ioFields=IOFieldTools.stepFinal(type.getFields(), List.of(
			IOFieldTools::dependencyReorder,
			IOFieldTools::mergeBitSpace
		));
	}
	
	@Override
	public void write(ContentWriter dest, T instance) throws IOException{
		if(DEBUG_VALIDATION){
			for(IOField<T, ?> field : ioFields){
				var buf=dest.writeTicket(field.calcByteSize(instance)).requireExact().submit();
				try{
					field.write(buf, instance);
				}catch(Exception e){
					throw new IOException("Failed to write "+TextUtil.toShortString(field), e);
				}
				buf.close();
			}
		}else{
			for(IOField<T, ?> field : ioFields){
				try{
					field.write(dest, instance);
				}catch(Exception e){
					throw new IOException("Failed to write "+TextUtil.toShortString(field), e);
				}
			}
		}
	}
	
	@Override
	public T read(ContentReader src, T instance) throws IOException{
		for(IOField<T, ?> field : ioFields){
			field.read(src, instance);
		}
		return instance;
	}
	
	@Override
	public long calcSize(T instance){
		long sum=0;
		for(IOField<T, ?> field : ioFields){
			sum+=field.calcSize(instance);
		}
		return sum;
	}
	
	@Override
	protected List<IOField<T, ?>> getSpecificFields(){
		return ioFields;
	}
}
