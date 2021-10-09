package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public class IOFieldDynamicInlineObject<CTyp extends IOInstance<CTyp>, ValueType extends IOInstance<ValueType>> extends IOField<CTyp, ValueType>{
	
	private final SizeDescriptor<CTyp> descriptor;
	
	public IOFieldDynamicInlineObject(IFieldAccessor<CTyp> accessor){
		super(accessor);
		
		if(getNullability()==IONullability.Mode.DEFAULT_IF_NULL){
			throw new MalformedStructLayout("DEFAULT_IF_NULL is not supported on dynamic fields!");
		}
		
		int idSize  =4;
		int nullSize=nullable()?1:0;
		
		Type type=accessor.getGenericType(null);
		
		
		long minKnownTypeSize=Long.MAX_VALUE;
		try{
			Struct<?>         struct =Struct.ofUnknown(Utils.typeToRaw(type));
			SizeDescriptor<?> typDesc=ContiguousStructPipe.of(struct).getSizeDescriptor();
			minKnownTypeSize=typDesc.getMin();
		}catch(IllegalArgumentException ignored){}
		
		var refDesc=ContiguousStructPipe.of(Reference.class).getSizeDescriptor();
		
		long minSize=idSize+nullSize+Math.min(refDesc.getMin(), minKnownTypeSize);
		
		descriptor=new SizeDescriptor.Unknown<>(minSize, OptionalLong.empty(), inst->{
			var val=get(inst);
			if(val==null) return nullSize;
			return nullSize+idSize+calcSize(val);
		});
	}
	
	@SuppressWarnings({"unchecked", "DuplicateBranchesInSwitch"})
	private static long calcSize(Object val){
		return switch(val){
			case Boolean ignored -> 1;
			case Integer ignored -> 4;
			case Float ignored -> 4;
			case Long ignored -> 8;
			case Double ignored -> 8;
			default -> throw new NotImplementedException(val.getClass()+"");
		};
	}
	
	@Override
	public ValueType get(CTyp instance){
		ValueType value=super.get(instance);
		return switch(getNullability()){
			case NOT_NULL -> requireValNN(value);
			case NULLABLE -> value;
			case DEFAULT_IF_NULL -> throw new ShouldNeverHappenError();
		};
	}
	
	@Override
	public void set(CTyp instance, ValueType value){
		super.set(instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public SizeDescriptor<CTyp> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public List<IOField<CTyp, ?>> write(ChunkDataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val=get(instance);
		throw new NotImplementedException();
	}
	
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		throw new NotImplementedException();
	}
	
	@Override
	public void skipRead(ChunkDataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		throw NotImplementedException.infer();//TODO: implement IOFieldDynamicInlineObject.skipRead()
	}
}
