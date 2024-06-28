package com.lapissea.dfs.type.field;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.MalformedPointer;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static com.lapissea.dfs.config.GlobalConfig.COSTLY_STACK_TRACE;

public final class VaryingSize implements Stringify{
	
	public static <T extends IOInstance<T>> TooSmall makeInvalid(FieldSet<T> fields, VarPool<T> ioPool, T instance, TooSmall e){
		var all = scanInvalidSizes(fields, ioPool, instance);
		if(e.tooSmallIdMap.equals(all.tooSmallIdMap)){
			throw e;
		}
		all.addSuppressed(e);
		return all;
	}
	
	private static <T extends IOInstance<T>> TooSmall scanInvalidSizes(FieldSet<T> fields, VarPool<T> ioPool, T instance){
		Map<VaryingSize, NumberSize> tooSmallIdMap = new HashMap<>();
		
		var provider = DataProvider.newVerySimpleProvider(makeFakeData());
		try(var blackHole = new ContentWriter(){
			@Override
			public void write(int b){ }
			@Override
			public void write(byte[] b, int off, int len){ }
		}){
			for(IOField<T, ?> field : fields){
				try{
					field.writeReported(ioPool, provider, blackHole, instance);
				}catch(TooSmall e){
					e.tooSmallIdMap.forEach((varying, size) -> {
						var num = tooSmallIdMap.get(varying);
						if(num == null) num = size;
						else if(num.greaterThan(size)) return;
						tooSmallIdMap.put(varying, num);
					});
				}catch(MalformedPointer badPtr){
					//Log.trace("Suppressed due to fake data: {} {}", badPtr, tooSmallIdMap);
				}
			}
		}catch(IOException e){
			throw new UncheckedIOException(e);
		}
		return new TooSmall(tooSmallIdMap);
	}
	
	private static IOInterface makeFakeData(){
		return new IOInterface(){
			@Override
			public boolean isReadOnly(){ return false; }
			@Override
			public IOTransaction openIOTransaction(){ return IOTransaction.NOOP; }
			@Override
			public RandomIO io(){
				return new RandomIO(){
					@Override
					public void setSize(long requestedSize){ }
					@Override
					public long getSize(){ return Long.MAX_VALUE; }
					@Override
					public long getPos(){ return 1; }
					@Override
					public RandomIO setPos(long pos){ return this; }
					@Override
					public long getCapacity(){ return Long.MAX_VALUE; }
					@Override
					public RandomIO setCapacity(long newCapacity){ return this; }
					@Override
					public void close(){ }
					@Override
					public void flush(){ }
					@Override
					public int read(){ return 0; }
					@Override
					public void write(int b){ }
					@Override
					public void writeAtOffsets(Collection<WriteChunk> data){ }
					@Override
					public void fillZero(long requestedMemory){ }
					@Override
					public boolean isReadOnly(){ return false; }
				};
			}
		};
	}
	
	public static final VaryingSize MAX = new VaryingSize(NumberSize.LARGEST, -1);
	
	private record UIDInfo(VaryingSize val, NumberSize max, boolean ptr){
		void validate(NumberSize max, boolean ptr){
			if(this.max != max) throw new IllegalStateException("Differing shared max");
			if(this.ptr != ptr) throw new IllegalStateException("Differing ptr");
		}
	}
	
	public interface Provider{
		
		final class Recorder implements Provider{
			
			public interface Mapper{
				NumberSize map(NumberSize max, boolean ptr, int id);
			}
			
			record Mark(List<NumberSize> data, Map<String, UIDInfo> uidMap){ }
			
			private final List<NumberSize> data = new ArrayList<>();
			private final Mapper           mapper;
			
			private final Map<Integer, Mark> marks = new HashMap<>();
			private       int                markUID;
			
			private final Map<String, UIDInfo> uidMap = new HashMap<>();
			
			public Recorder(Mapper mapper){
				this.mapper = mapper;
			}
			
			@Override
			public VaryingSize provide(NumberSize max, String uid, boolean ptr){
				var repeat = uidMap.get(uid);
				if(repeat != null){
					repeat.validate(max, ptr);
					return repeat.val;
				}
				
				var id     = data.size();
				var actual = mapper.map(max, ptr, id);
				data.add(actual);
				
				var result = new VaryingSize(actual, id);
				if(uid != null) uidMap.put(uid, new UIDInfo(result, max, ptr));
				return result;
			}
			
			@Override
			public int mark(){
				int id = markUID++;
				marks.put(id, new Mark(List.copyOf(data), Map.copyOf(uidMap)));
				return id;
			}
			@Override
			public void reset(int id){
				var mark = Objects.requireNonNull(marks.remove(id), "Mark does not exist");
				data.clear();
				data.addAll(mark.data);
				uidMap.clear();
				uidMap.putAll(mark.uidMap);
			}
			
			public List<NumberSize> export(){
				return List.copyOf(data);
			}
		}
		
		final class Repeater implements Provider{
			
			private record Mark(int counter, Map<String, UIDInfo> uidMap){ }
			
			private final List<NumberSize> data;
			private       int              counter;
			
			private final Map<Integer, Mark> marks = new HashMap<>();
			private       int                markIdCount;
			
			private final Map<String, UIDInfo> uidMap = new HashMap<>();
			
			public Repeater(List<NumberSize> data){
				this.data = List.copyOf(data);
			}
			
			@Override
			public VaryingSize provide(NumberSize max, String uid, boolean ptr){
				var repeat = uidMap.get(uid);
				if(repeat != null){
					repeat.validate(max, ptr);
					return repeat.val;
				}
				
				var result = new VaryingSize(data.get(counter), counter);
				
				if(uid != null) uidMap.put(uid, new UIDInfo(result, max, ptr));
				counter++;
				
				return result;
			}
			
			@Override
			public int mark(){
				int id = markIdCount++;
				marks.put(id, new Mark(counter, Map.copyOf(uidMap)));
				return id;
			}
			@Override
			public void reset(int id){
				var mark = Objects.requireNonNull(marks.remove(id), "Mark does not exist");
				counter = mark.counter;
				uidMap.clear();
				uidMap.putAll(mark.uidMap);
			}
		}
		
		Provider ALL_MAX = new NoMark(){
			@Override
			public VaryingSize provide(NumberSize max, String uid, boolean ptr){
				return new VaryingSize(max, -1);
			}
			@Override
			public String toString(){
				return "ALL_MAX";
			}
		};
		
		static Recorder record(Recorder.Mapper mapper){
			return new Recorder(mapper);
		}
		static Provider repeat(List<NumberSize> data){
			return new Repeater(data);
		}
		
		static Provider constLimit(NumberSize size, int id){
			if(size == NumberSize.LARGEST) return ALL_MAX;
			return new NoMark(){
				@Override
				public VaryingSize provide(NumberSize max, String uid, boolean ptr){
					return new VaryingSize(max.min(size), id);
				}
				@Override
				public String toString(){
					return "ConstLimit(" + size + ")";
				}
			};
		}
		
		interface Intercept<T>{
			void intercept(NumberSize max, boolean ptr, VaryingSize actual, T state);
		}
		
		class InterceptProvider<T> implements Provider{
			private final Provider       src;
			private final Intercept<T>   intercept;
			private       T              state;
			private final Function<T, T> cloneState;
			
			
			record Mark<T>(Set<String> uids, T state){ }
			
			private final Set<String>           uids  = new HashSet<>();
			private final Map<Integer, Mark<T>> marks = new HashMap<>();
			
			public InterceptProvider(Provider src, Intercept<T> intercept, T initialState, Function<T, T> cloneState){
				this.src = src;
				this.intercept = intercept;
				this.state = initialState;
				this.cloneState = cloneState;
			}
			
			@Override
			public VaryingSize provide(NumberSize max, String uid, boolean ptr){
				var actual = src.provide(max, uid, ptr);
				if(uid == null || uids.add(uid)){
					intercept.intercept(max, ptr, actual, state);
				}
				return actual;
			}
			@Override
			public int mark(){
				var m = src.mark();
				marks.put(m, new Mark<>(Set.copyOf(uids), cloneState.apply(state)));
				return m;
			}
			@Override
			public void reset(int id){
				src.reset(id);
				var mark = Objects.requireNonNull(marks.get(id));
				uids.clear();
				uids.addAll(mark.uids);
				state = mark.state;
			}
			
			public T getState(){
				return cloneState.apply(state);
			}
		}
		
		static <T> InterceptProvider<T> intercept(Provider src, Intercept<T> intercept, T interceptState, Function<T, T> cloneState){
			return new InterceptProvider<>(src, intercept, interceptState, cloneState);
		}
		VaryingSize provide(NumberSize max, String uid, boolean ptr);
		
		interface NoMark extends Provider{
			@Override
			default int mark(){ return 0; }
			@Override
			default void reset(int id){ }
		}
		
		/**
		 * Notates the start of an object.
		 *
		 * @return a unique ID
		 */
		int mark();
		/**
		 * Ignores all sizes since the start of the mark. Use when creating an object fails.
		 */
		void reset(int id);
	}
	
	public static final class TooSmall extends RuntimeException{
		public final Map<VaryingSize, NumberSize> tooSmallIdMap;
		
		public TooSmall(Map<VaryingSize, NumberSize> tooSmallIdMap){
			this.tooSmallIdMap = Map.copyOf(tooSmallIdMap);
		}
		public TooSmall(VaryingSize size, NumberSize neededSize){
			this(Map.of(size, neededSize));
		}
		@Override
		public Throwable fillInStackTrace(){
			if(COSTLY_STACK_TRACE) return super.fillInStackTrace();
			return this;
		}
		@Override
		public String toString(){
			return this.getClass().getSimpleName() + ": " + TextUtil.toString(tooSmallIdMap);
		}
	}
	
	public final NumberSize size;
	public final int        id;
	
	public VaryingSize(NumberSize size, int id){
		if(id<0 && id != -1) throw new IllegalArgumentException();
		this.size = Objects.requireNonNull(size);
		this.id = id;
	}
	
	public NumberSize safeNumber(long neededNum){
		return safeSize(NumberSize.bySize(neededNum));
	}
	public NumberSize safeSize(NumberSize neededSize){
		if(neededSize.greaterThan(size)){
			if(id == -1) throw new UnsupportedOperationException();
			throw new TooSmall(this, neededSize);
		}
		return size;
	}
	
	@Override
	public String toString(){
		return "VaryingSize" + toShortString();
	}
	@Override
	public String toShortString(){
		return "{" + size + (id == -1? "" : " @" + id) + '}';
	}
	
	@Override
	public boolean equals(Object o){
		return this == o || o instanceof VaryingSize that && id == that.id && size == that.size;
		
	}
	@Override
	public int hashCode(){
		return 31*size.hashCode() + id;
	}
}
