package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalLong;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.*;

public class IOFieldInlineObject<CTyp extends IOInstance<CTyp>, ValueType extends IOInstance<ValueType>> extends IOField<CTyp, ValueType>{
	
	private final SizeDescriptor<CTyp>  descriptor;
	private final StructPipe<ValueType> instancePipe;
	private final IONullability.Mode    nullability;
	private final boolean               fixed;
	
	public IOFieldInlineObject(IFieldAccessor<CTyp> accessor){
		this(accessor, false);
	}
	public IOFieldInlineObject(IFieldAccessor<CTyp> accessor, boolean fixed){
		super(accessor);
		this.fixed=fixed;
		
		nullability=accessor.getAnnotation(IONullability.class).map(IONullability::value).orElse(IONullability.Mode.NOT_NULL);
		
		var struct=(Struct<ValueType>)Struct.ofUnknown(getAccessor().getType());
		instancePipe=fixed?FixedContiguousStructPipe.of(struct):ContiguousStructPipe.of(struct);
		
		int nullSize=nullability==NULLABLE?1:0;
		
		if(fixed||instancePipe.getSizeDescriptor().hasFixed()){
			descriptor=new SizeDescriptor.Fixed<>(instancePipe.getSizeDescriptor().requireFixed()+nullSize);
		}else{
			var desc=instancePipe.getSizeDescriptor();
			descriptor=new SizeDescriptor.Unknown<>(desc.getWordSpace(), nullability==NULLABLE?nullSize:desc.getMin(), Utils.addIfBoth(OptionalLong.of(nullSize), desc.getMax())){
				@Override
				public long calcUnknown(CTyp instance){
					return desc.calcUnknown(get(instance))+nullSize;
				}
			};
		}
	}
	
	@Override
	public ValueType get(CTyp instance){
		ValueType value=super.get(instance);
		return switch(nullability){
			case NOT_NULL -> Objects.requireNonNull(value);
			case NULLABLE -> value;
			case DEFAULT_IF_NULL -> {
				if(value==null){
					yield instancePipe.getType().requireEmptyConstructor().get();
				}
				yield value;
			}
		};
	}
	
	@Override
	public void set(CTyp instance, ValueType value){
		super.set(instance, switch(nullability){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public IOField<CTyp, ValueType> implMaxAsFixedSize(){
		return new IOFieldInlineObject<>(getAccessor(), true);
	}
	
	@Override
	public SizeDescriptor<CTyp> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public void write(ContentWriter dest, CTyp instance) throws IOException{
		var val=get(instance);
		if(nullability==NULLABLE){
			try(var flags=new FlagWriter.AutoPop(NumberSize.BYTE, dest)){
				flags.writeBoolBit(val==null);
			}
			if(fixed){
				Utils.zeroFill(dest::write, (int)getSizeDescriptor().requireFixed()-1);
			}
			if(val==null) return;
		}
		instancePipe.write(dest, val);
	}
	
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, CTyp instance) throws IOException{
		if(nullability==NULLABLE){
			try(var flags=FlagReader.read(src, NumberSize.BYTE)){
				if(flags.readBoolBit()){
					set(instance, null);
					if(fixed){
						src.readInts1((int)getSizeDescriptor().requireFixed()-1);
					}
					return;
				}
			}
		}
		
		set(instance, instancePipe.readNew(provider, src));
	}
}
