package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.util.UtilL;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public interface RegistryNode{
	interface InstanceOf<ValTyp> extends RegistryNode{
		Class<ValTyp> getType();
		@Override
		default boolean canCreate(Type type, GetAnnotation annotations){
			return UtilL.instanceOf(Utils.typeToRaw(type), getType());
		}
		@Override
		<T extends IOInstance<T>> IOField<T, ? extends ValTyp> create(IFieldAccessor<T> field, GenericContext genericContext);
	}
	
	class Registry implements RegistryNode{
		
		private final List<RegistryNode> nodes=new ArrayList<>();
		
		public void register(RegistryNode node){
			nodes.add(node);
		}
		
		private MalformedStructLayout fail(Type type){
			throw new MalformedStructLayout("Unable to find implementation of "+IOField.class.getSimpleName()+" from "+type);
		}
		private RegistryNode find(Type type, GetAnnotation annotation){
			for(var node : nodes){
				if(node.canCreate(type, annotation)) return node;
			}
			return null;
		}
		
		public void requireCanCreate(Type type, GetAnnotation annotation){
			if(!canCreate(type, annotation)){
				throw fail(type);
			}
		}
		
		@Override
		public boolean canCreate(Type type, GetAnnotation annotations){
			return find(type, annotations)!=null;
		}
		
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field, GenericContext genericContext){
			var node=find(field.getGenericType(genericContext), GetAnnotation.from(field));
			if(node!=null) return node.create(field, genericContext);
			throw fail(field.getGenericType(genericContext));
		}
	}
	
	boolean canCreate(Type type, GetAnnotation annotations);
	<T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field, GenericContext genericContext);
}
