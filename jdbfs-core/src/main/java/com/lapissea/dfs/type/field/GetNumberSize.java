package com.lapissea.dfs.type.field;

import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.access.AnnotatedType;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.List;

//TODO: Benchmark this class, see if generators are viable. If so, completely do away with the concept of filters.
// Also benchmark if CatchAll is enough or if specialized implementations are worth the code.
abstract sealed class GetNumberSize<T extends IOInstance<T>> implements VirtualFieldDefinition.GetterFilter<T, NumberSize>{
	
	static final class Uninitialized<T extends IOInstance<T>> extends GetNumberSize<T>{
		
		Uninitialized(NumberSize min, NumberSize max, boolean unsigned){ super(min, max, unsigned); }
		
		@Override
		protected long calcMaxVal(VarPool<T> ioPool, T instance){ throw new UnsupportedOperationException(); }
		@Override
		protected long calcMinMaxVal(VarPool<T> ioPool, T instance){ throw new UnsupportedOperationException(); }
		
		@Override
		public VirtualFieldDefinition.GetterFilter<T, NumberSize> withUsers(List<FieldAccessor<T>> users){
			return switch(users.size()){
				case 0 -> throw new UnsupportedOperationException();
				case 1 -> {
					var user = users.getFirst();
					var type = user.getType();
					if(type == ChunkPointer.class){
						yield new SinglePtr<>(min, max, unsigned, user);
					}
					yield new SingleNum<>(min, max, unsigned, user);
				}
				default -> {
					var types = Iters.from(users).map(AnnotatedType::getType).distinct().toList();
					if(types.size() == 1){
						var type = types.getFirst();
						if(type == int.class){
							yield new Ints<>(min, max, unsigned, users);
						}
					}
					yield new CatchAll<>(min, max, unsigned, users);
				}
			};
		}
	}
	
	private abstract static sealed class Inited<T extends IOInstance<T>> extends GetNumberSize<T>{
		
		private Inited(NumberSize min, NumberSize max, boolean unsigned){
			super(min, max, unsigned);
		}
		
		@Override
		public VirtualFieldDefinition.GetterFilter<T, NumberSize> withUsers(List<FieldAccessor<T>> users){
			throw new UnsupportedOperationException("Already initialized");
		}
	}
	
	private static final class SingleNum<T extends IOInstance<T>> extends Inited<T>{
		private final FieldAccessor<T> user;
		
		private SingleNum(NumberSize min, NumberSize max, boolean unsigned, FieldAccessor<T> user){
			super(min, max, unsigned);
			this.user = user;
		}
		
		@Override
		protected long calcMinMaxVal(VarPool<T> ioPool, T instance){ return calcMaxVal(ioPool, instance); }
		@Override
		protected long calcMaxVal(VarPool<T> ioPool, T instance){ return user.getLong(ioPool, instance); }
	}
	
	private static final class SinglePtr<T extends IOInstance<T>> extends Inited<T>{
		private final FieldAccessor<T> user;
		
		private SinglePtr(NumberSize min, NumberSize max, boolean unsigned, FieldAccessor<T> user){
			super(min, max, unsigned);
			this.user = user;
		}
		
		@Override
		protected long calcMinMaxVal(VarPool<T> ioPool, T instance){ return calcMaxVal(ioPool, instance); }
		@Override
		protected long calcMaxVal(VarPool<T> ioPool, T instance){
			var ptr = user.get(ioPool, instance);
			return ((ChunkPointer)ptr).getValue();
		}
	}
	
	private static final class Ints<T extends IOInstance<T>> extends Inited<T>{
		private final FieldAccessor<T>[] users;
		
		private Ints(NumberSize min, NumberSize max, boolean unsigned, List<FieldAccessor<T>> users){
			super(min, max, unsigned);
			//noinspection unchecked
			this.users = users.toArray(new FieldAccessor[0]);
		}
		
		@Override
		protected long calcMaxVal(VarPool<T> ioPool, T instance){
			int max = 0;
			for(var user : users){
				int newVal = user.getInt(ioPool, instance);
				max = Math.max(max, newVal);
			}
			return max;
		}
		@Override
		protected long calcMinMaxVal(VarPool<T> ioPool, T instance){
			int min = Integer.MAX_VALUE;
			int max = Integer.MIN_VALUE;
			for(var user : users){
				int newVal = user.getInt(ioPool, instance);
				min = Math.min(min, newVal);
				max = Math.max(max, newVal);
			}
			return Math.abs(min)>Math.abs(max)? min : max;
		}
	}
	
	private static final class CatchAll<T extends IOInstance<T>> extends Inited<T>{
		private final FieldAccessor<T>[] numbers;
		private final FieldAccessor<T>[] pointers;
		
		@SuppressWarnings("unchecked")
		private CatchAll(NumberSize min, NumberSize max, boolean unsigned, List<FieldAccessor<T>> users){
			super(min, max, unsigned);
			var usr = Iters.from(users);
			this.numbers = usr.filter(f -> f.getType() != ChunkPointer.class).toArray(FieldAccessor[]::new);
			this.pointers = usr.filter(f -> f.getType() == ChunkPointer.class).toArray(FieldAccessor[]::new);
		}
		
		@Override
		protected long calcMaxVal(VarPool<T> ioPool, T instance){
			long max = 0;
			for(var user : numbers){
				long newVal = user.getLong(ioPool, instance);
				max = Math.max(max, newVal);
			}
			for(var user : pointers){
				var  ptr    = user.get(ioPool, instance);
				long newVal = ((ChunkPointer)ptr).getValue();
				max = Math.max(max, newVal);
			}
			return max;
		}
		@Override
		protected long calcMinMaxVal(VarPool<T> ioPool, T instance){
			long min = Long.MAX_VALUE;
			long max = Long.MIN_VALUE;
			for(var user : numbers){
				long newVal = user.getLong(ioPool, instance);
				min = Math.min(min, newVal);
				max = Math.max(max, newVal);
			}
			for(var user : pointers){
				var  ptr    = user.get(ioPool, instance);
				long newVal = ((ChunkPointer)ptr).getValue();
				min = Math.min(min, newVal);
				max = Math.max(max, newVal);
			}
			return Math.abs(min)>Math.abs(max)? min : max;
		}
	}
	
	protected final NumberSize min;
	protected final NumberSize max;
	protected final boolean    unsigned;
	
	private GetNumberSize(NumberSize min, NumberSize max, boolean unsigned){
		this.min = min;
		this.max = max;
		this.unsigned = unsigned;
	}
	
	@Override
	public final NumberSize filter(VarPool<T> ioPool, T instance, NumberSize value){
		var raw  = value == null? calcMax(ioPool, instance) : value;
		var size = raw.max(min);
		
		if(size.greaterThan(max)){
			throw new RuntimeException(size + " can't fit in to " + max);
		}
		return size;
	}
	private NumberSize calcMax(VarPool<T> ioPool, T inst){
		if(unsigned){
			var len = calcMaxVal(ioPool, inst);
			return NumberSize.bySize(Math.max(0, len));
		}else{
			var len = calcMinMaxVal(ioPool, inst);
			return NumberSize.bySizeSigned(len);
		}
	}
	
	protected abstract long calcMaxVal(VarPool<T> ioPool, T instance);
	protected abstract long calcMinMaxVal(VarPool<T> ioPool, T instance);
}
