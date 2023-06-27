package com.lapissea.cfs.type;

public interface RuntimeType<T>{
	
	abstract class Abstract<L> implements RuntimeType<L>{
		
		private final Class<L> typ;
		protected Abstract(Class<L> typ){ this.typ = typ; }
		
		@Override
		public Class<L> getType(){
			return typ;
		}
		
		@Override
		public boolean equals(Object o){
			return this == o ||
			       o instanceof RuntimeType<?> runtimeType &&
			       getType().equals(runtimeType.getType());
		}
		
		@Override
		public int hashCode(){
			return typ != null? typ.hashCode() : 0;
		}
	}
	
	class Lambda<L> extends Abstract<L>{
		
		private final NewObj<L> sup;
		
		Lambda(Class<L> typ, NewObj<L> sup){
			super(typ);
			this.sup = sup;
		}
		
		@Override
		public boolean getCanHavePointers(){
			return false;
		}
		@Override
		public NewObj<L> emptyConstructor(){
			return sup;
		}
	}
	
	@SuppressWarnings("unchecked")
	static <T> RuntimeType<T> of(Class<T> type){
		if(IOInstance.isInstance(type)){
			return (RuntimeType<T>)Struct.ofUnknown(type);
		}
		
		return SupportedPrimitive.get(type).map(t -> (RuntimeType<T>)t).orElseGet(() -> new Abstract<>(type){
			private NewObj<T> sup;
			
			@Override
			public boolean getCanHavePointers(){
				return false;
			}
			@Override
			public NewObj<T> emptyConstructor(){
				if(sup == null){
					try{
						var constr = getType().getConstructor();
						sup = () -> {
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
	NewObj<T> emptyConstructor();
	Class<T> getType();
	
	default T make(){
		return emptyConstructor().make();
	}
}
