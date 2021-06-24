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

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public class IOFieldInlineObject<CTyp extends IOInstance<CTyp>, ValueType extends IOInstance<ValueType>> extends IOField<CTyp, ValueType>{
	
	private final SizeDescriptor<CTyp>  descriptor;
	private final StructPipe<ValueType> instancePipe;
	private final boolean               fixed;
	
	public IOFieldInlineObject(IFieldAccessor<CTyp> accessor){
		this(accessor, false);
	}
	public IOFieldInlineObject(IFieldAccessor<CTyp> accessor, boolean fixed){
		super(accessor);
		this.fixed=fixed;
		
		
		var struct=(Struct<ValueType>)Struct.ofUnknown(getAccessor().getType());
		instancePipe=fixed?FixedContiguousStructPipe.of(struct):ContiguousStructPipe.of(struct);
		
		int nullSize=nullable()?1:0;
		
		if(fixed||instancePipe.getSizeDescriptor().hasFixed()){
			descriptor=new SizeDescriptor.Fixed<>(instancePipe.getSizeDescriptor().requireFixed()+nullSize);
		}else{
			var desc=instancePipe.getSizeDescriptor();
			descriptor=new SizeDescriptor.Unknown<>(desc.getWordSpace(), nullable()?nullSize:desc.getMin(), Utils.addIfBoth(OptionalLong.of(nullSize), desc.getMax())){
				@Override
				public long calcUnknown(CTyp instance){
					var val=get(instance);
					if(val==null){
						if(nullable()) return nullSize;
						throw new NullPointerException();
					}
					return desc.calcUnknown(val)+nullSize;
				}
			};
		}
	}
	
	@Override
	public ValueType get(CTyp instance){
		ValueType value=super.get(instance);
		return switch(getNullability()){
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
		super.set(instance, switch(getNullability()){
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
	public List<IOField<CTyp, ?>> write(ChunkDataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val=get(instance);
		if(nullable()){
			try(var flags=new FlagWriter.AutoPop(NumberSize.BYTE, dest)){
				flags.writeBoolBit(val==null);
			}
			if(fixed){
				Utils.zeroFill(dest::write, (int)getSizeDescriptor().requireFixed()-1);
			}
			if(val==null) return List.of();
		}
		instancePipe.write(provider, dest, val);
		return List.of();
	}
	
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, CTyp instance) throws IOException{
		if(nullable()){
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
