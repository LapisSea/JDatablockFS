package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;

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
		for(IOField<T, ?> field : ioFields){
			if(DEBUG_VALIDATION){
				var buf=dest.writeTicket(field.getSizeDescriptor().variableBytes(instance)).requireExact().submit();
				field.writeReported(buf, instance);
				buf.close();
			}else{
				field.writeReported(dest, instance);
			}
		}
	}
	
	@Override
	public T read(ContentReader src, T instance) throws IOException{
		for(IOField<T, ?> field : ioFields){
			if(DEBUG_VALIDATION){
				var buf=src.readTicket(field.getSizeDescriptor().variableBytes(instance)).requireExact().submit();
				field.readReported(buf, instance);
				buf.close();
			}else{
				field.readReported(src, instance);
			}
		}
		return instance;
	}
	
	@Override
	protected List<IOField<T, ?>> getSpecificFields(){
		return ioFields;
	}
}
