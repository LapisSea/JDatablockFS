package com.lapissea.cfs.type.field.fields.reflection.wrappers;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.BehaviourSupport;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IOIndexed;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class IOFieldInlineString<CTyp extends IOInstance<CTyp>> extends NullFlagCompanyField<CTyp, String>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<String>{
		public Usage(){ super(String.class, Set.of(IOFieldInlineString.class, IOFieldIndexedString.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, String> create(FieldAccessor<T> field){
			if(field.hasAnnotation(IOIndexed.class)){
				return new IOFieldIndexedString<>(field, null);
			}
			return new IOFieldInlineString<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability),
				Behaviour.noop(IOIndexed.class)
			);
		}
	}
	
	public IOFieldInlineString(FieldAccessor<CTyp> accessor){
		super(accessor);
		
		assert !accessor.hasAnnotation(IOIndexed.class);
		
		var desc = AutoText.PIPE.getSizeDescriptor();
		
		initSizeDescriptor(SizeDescriptor.Unknown.of(
			desc.getWordSpace(),
			nullable()? 0 : desc.getMin(),
			desc.getMax(),
			(ioPool, prov, inst) -> {
				var val = get(null, inst);
				if(val == null){
					if(nullable()) return 0;
					throw new NullPointerException();
				}
				return AutoText.PIPE.calcUnknownSize(prov, new AutoText(val), desc.getWordSpace());
			}
		));
	}
	
	@Override
	public String get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, () -> "");
	}
	@Override
	public boolean isNull(VarPool<CTyp> ioPool, CTyp instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, String value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		if(nullable() && getIsNull(ioPool, instance)) return;
		var val = get(ioPool, instance);
		if(val == null && !nullable()){
			throw new NullPointerException();
		}
		AutoText.PIPE.write(provider, dest, new AutoText(val));
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		String text;
		if(nullable() && getIsNull(ioPool, instance)) text = null;
		else{
			var wrap = AutoText.PIPE.readNew(provider, src, genericContext);
			text = wrap.getData();
		}
		
		set(ioPool, instance, text);
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(getIsNull(ioPool, instance)) return;
		}
		AutoText.PIPE.skip(provider, src, genericContext);
	}
	
	@Override
	protected void throwInformativeFixedSizeError(){
		//TODO
		throw new RuntimeException("Strings do not support fixed size yet. In future a max string size will be defined by the user if they wish for it to be fixed size compatible.");
	}
}
