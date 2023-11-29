package com.lapissea.dfs.type.field.fields.reflection.wrappers;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.text.AutoText;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class IOFieldInlineString<CTyp extends IOInstance<CTyp>> extends NullFlagCompanyField<CTyp, String>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<String>{
		public Usage(){ super(String.class, Set.of(IOFieldInlineString.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, String> create(FieldAccessor<T> field){
			return new IOFieldInlineString<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(Behaviour.of(IONullability.class, BehaviourSupport::ioNullability));
		}
	}
	
	public IOFieldInlineString(FieldAccessor<CTyp> accessor){
		super(accessor);
		
		
		var desc = AutoText.PIPE.getSizeDescriptor();
		
		initSizeDescriptor(SizeDescriptor.Unknown.of(
			desc.getWordSpace(),
			nullable()? 0 : desc.getMin(),
			desc.getMax(),
			(ioPool, prov, inst) -> {
				var val = getWrapped(null, inst);
				if(val == null){
					if(nullable()) return 0;
					throw new NullPointerException();
				}
				return AutoText.PIPE.calcUnknownSize(prov, val, desc.getWordSpace());
			}
		));
	}
	
	private AutoText getWrapped(VarPool<CTyp> ioPool, CTyp instance){
		var raw = get(ioPool, instance);
		if(raw == null) return null;
		return new AutoText(raw);
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
		var val = getWrapped(ioPool, instance);
		if(val == null && !nullable()){
			throw new NullPointerException();
		}
		AutoText.PIPE.write(provider, dest, val);
	}
	
	private AutoText readNew(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(getIsNull(ioPool, instance)) return null;
		}
		
		return AutoText.PIPE.readNew(provider, src, genericContext);
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		var text = readNew(ioPool, provider, src, instance, genericContext);
		set(ioPool, instance, text == null? null : text.getData());
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
