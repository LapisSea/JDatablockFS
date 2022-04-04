package com.lapissea.cfs.type;

import java.util.function.Supplier;

public interface RuntimeType<T>{
	
	abstract class Abstract<L> implements RuntimeType<L>{
		
		private final Class<L> typ;
		protected Abstract(Class<L> typ){this.typ=typ;}
		
		@Override
		public Class<L> getType(){
			return typ;
		}
		
		@Override
		public boolean equals(Object o){
			return this==o||
			       o instanceof RuntimeType<?> typ&&
			       getType().equals(typ.getType());
		}
		
		@Override
		public int hashCode(){
			return typ!=null?typ.hashCode():0;
		}
	}
	
	class Lambda<L> extends Abstract<L>{
		
		private final Supplier<L> sup;
		
		Lambda(Class<L> typ, Supplier<L> sup){
			super(typ);
			this.sup=sup;
		}
		
		@Override
		public boolean getCanHavePointers(){
			return false;
		}
		@Override
		public Supplier<L> requireEmptyConstructor(){
			return sup;
		}
	}
	
	@SuppressWarnings("unchecked")
	static <T> RuntimeType<T> of(Class<T> type){
		if(IOInstance.isInstance(type)){
			return (RuntimeType<T>)Struct.ofUnknown(type);
		}
		
		return SupportedPrimitive.get(type).map(t->(RuntimeType<T>)t.runtimeType).orElseGet(()->new Abstract<>(type){
			private Supplier<T> sup;
			
			@Override
			public boolean getCanHavePointers(){
				return false;
			}
			@Override
			public Supplier<T> requireEmptyConstructor(){
				if(sup==null){
					try{
						var constr=getType().getConstructor();
						sup=()->{
							try{
								return constr.newInstance();
							}catch(ReflectiveOperationException e){
								throw new RuntimeException(e);
							}
						};
					}catch(NoSuchMethodException e){
						throw new RuntimeException(e);
					}
				}
				return sup;
			}
		});
	}
	
	
	boolean getCanHavePointers();
	Supplier<T> requireEmptyConstructor();
	Class<T> getType();
}
