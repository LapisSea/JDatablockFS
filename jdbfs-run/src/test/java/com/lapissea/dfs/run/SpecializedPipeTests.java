package com.lapissea.dfs.run;

import com.lapissea.dfs.SyntheticParameterizedType;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.io.instancepipe.CheckedPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.ObjectID;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.Plan;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class SpecializedPipeTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }

//	@BeforeClass
//	void before(){
//		StandardStructPipe.of(Chunk.class, StructPipe.STATE_IO_FIELD);
//	}
	
	private static final Annotation UNSIGNED = Annotations.make(IOValue.Unsigned.class);
	
	record FieldDef(
		String name,
		Class<?> type, boolean getter, boolean setter, List<Annotation> annotations,
		Function<RandomGenerator, Object> generator
	){
		FieldDef(Class<?> type, Function<RandomGenerator, Object> generator){ this("val", type, false, false, List.of(), generator); }
		
		FieldDef withType(Class<?> type)                                    { return new FieldDef(name, type, getter, setter, annotations, generator); }
		FieldDef withGetter(boolean getter)                                 { return new FieldDef(name, type, getter, setter, annotations, generator); }
		FieldDef withSetter(boolean setter)                                 { return new FieldDef(name, type, getter, setter, annotations, generator); }
		FieldDef withAnnotations(List<Annotation> ann)                      { return new FieldDef(name, type, getter, setter, ann, generator); }
		FieldDef withNewAnnotations(Annotation... ann)                      { return withAnnotations(Iters.concat(annotations, Iters.from(ann)).toList()); }
		FieldDef withGenerator(Function<RandomGenerator, Object> generator) { return new FieldDef(name, type, getter, setter, annotations, generator); }
		FieldDef withName(String name)                                      { return new FieldDef(name, type, getter, setter, annotations, generator); }
		
		@Override
		public String toString(){
			var res = new StringJoiner(", ", type.getTypeName() + "{", "}");
			if(getter) res.add("withGet");
			if(setter) res.add("withSet");
			Iters.from(annotations).joinAsOptionalStr(", ", "anns: [", "]", ann -> ann.annotationType().getSimpleName()).ifPresent(res::add);
			return res.toString();
		}
	}
	
	@org.testng.annotations.DataProvider
	Object[][] fieldVariations(){
		var all = fieldPermutations();
		return all.flatMapArray(e -> new Object[][]{{e, true}, {e, false}}).toArray(Object[].class);
	}
	
	private static IterablePP.SizedPP<FieldDef> fieldPermutations(){
		IterablePP<FieldDef> doubles  = withFns(Iters.of(new FieldDef(double.class, RandomGenerator::nextDouble)));
		IterablePP<FieldDef> chars    = withFns(Iters.of(new FieldDef(char.class, r -> (char)r.nextInt(255))));
		IterablePP<FieldDef> floats   = withFns(Iters.of(new FieldDef(float.class, RandomGenerator::nextFloat)));
		IterablePP<FieldDef> longs    = withFns(Iters.of(new FieldDef(long.class, RandomGenerator::nextLong)));
		IterablePP<FieldDef> ints     = withFns(Iters.of(new FieldDef(int.class, RandomGenerator::nextInt)));
		IterablePP<FieldDef> shorts   = withFns(Iters.of(new FieldDef(short.class, r -> (short)r.nextInt())));
		IterablePP<FieldDef> bytes    = withFns(Iters.of(new FieldDef(byte.class, r -> (byte)r.nextInt())));
		IterablePP<FieldDef> booleans = withFns(Iters.of(new FieldDef(boolean.class, RandomGenerator::nextBoolean)));
		
		longs = withUnsigned(longs, r -> r.nextLong(0, Long.MAX_VALUE));
		ints = withUnsigned(ints, r -> r.nextInt(0, Integer.MAX_VALUE));
		shorts = withUnsigned(shorts, r -> (short)r.nextInt(0, Short.MAX_VALUE));
		
		chars = withVirtualSize(chars);
		longs = withVirtualSize(longs);
		ints = withVirtualSize(ints);
		shorts = withVirtualSize(shorts);
		
		var primitives = Iters.concat(
//			bytes
			doubles, chars, floats, longs, ints, shorts, bytes, booleans
		);
		
		IterablePP<FieldDef> boxed = primitives.map(e -> e.withType(SupportedPrimitive.get(e.type).orElseThrow().wrapper));
		boxed = withNullable(boxed);
		
		
		IterablePP<FieldDef> strings = withFns(Iters.of(new FieldDef(String.class, r -> {
			return r.ints(r.nextInt(1, 10), 'A', 'z').mapToObj(i -> ((char)i) + "").collect(Collectors.joining());
		})));
		
		IterablePP<FieldDef> dynamic = withFns(Iters.of(new FieldDef(Object.class, r -> switch(r.nextInt(4)){
			case 0 -> r.nextInt();
			case 1 -> r.nextBoolean();
			case 2 -> new Reference(ChunkPointer.of(r.nextInt(Integer.MAX_VALUE)), r.nextInt(Integer.MAX_VALUE));
			case 3 -> new ObjectID.LID(r.nextLong());
			default -> throw new NotImplementedException();
		}).withAnnotations(List.of(Annotations.make(IOValue.Generic.class)))));
		
		IterablePP<FieldDef> directType = withFns(Iters.of(
			new FieldDef(Class.class, r -> switch(r.nextInt(3)){
				case 0 -> int.class;
				case 1 -> List.class;
				case 2 -> Reference.class;
				default -> throw new NotImplementedException();
			}),
			new FieldDef(Type.class, r -> switch(r.nextInt(5)){
				case 0 -> int.class;
				case 1 -> List.class;
				case 2 -> Reference.class;
				case 3 -> SyntheticParameterizedType.of(List.class, List.of(ObjectID.class));
				case 4 -> SyntheticParameterizedType.of(Map.class, List.of(ObjectID.class, Long.class));
				default -> throw new NotImplementedException();
			})
		)).map(e -> e.withAnnotations(List.of(Annotations.make(IOUnsafeValue.class))));
		
		IterablePP<FieldDef> objects = Iters.concat(
			strings, dynamic, directType
		);
		
		objects = withNullable(objects);
		
		return Iters.concat(
			primitives, boxed, objects
		);
	}
	
	private static IterablePP<FieldDef> withFns(IterablePP<FieldDef> combinations){
		combinations = Iters.concat(
			combinations.map(e -> e.withGetter(false)),
			combinations.map(e -> e.withGetter(true))
		);
		combinations = Iters.concat(
			combinations.map(e -> e.withSetter(false)),
			combinations.map(e -> e.withSetter(true))
		);
		return combinations;
	}
	private static IterablePP<FieldDef> withUnsigned(IterablePP<FieldDef> combinations, Function<RandomGenerator, Object> generator){
		return Iters.concat(
			combinations.map(e -> e.withAnnotations(List.of())),
			combinations.map(e -> e.withAnnotations(List.of(UNSIGNED))
			                       .withGenerator(generator))
		);
	}
	private static IterablePP<FieldDef> withVirtualSize(IterablePP<FieldDef> combinations){
		return Iters.concat(
			combinations,
			combinations.map(e -> e.withNewAnnotations(Annotations.make(IODependency.VirtualNumSize.class)))
		);
	}
	private static IterablePP<FieldDef> withNullable(IterablePP<FieldDef> combinations){
		return Iters.concat(
			combinations,
			combinations.map(e -> {
				var oldGen = e.generator;
				return e.withNewAnnotations(Annotations.make(IONullability.class, Map.of("value", IONullability.Mode.NULLABLE)))
				        .withGenerator(r -> r.nextBoolean()? oldGen.apply(r) : null);
			})
		);
	}
	
	public static class SoftRetry implements IRetryAnalyzer{
		
		private static final int maxRetryCount = 5;
		
		record Entry(String name, List<Object> args){ }
		
		private final Map<Entry, Integer> retryRecord = new ConcurrentHashMap<>();
		
		@Override
		public boolean retry(ITestResult result){
			if(!(result.getThrowable() instanceof AssertionError a && a.getMessage().contains("DEBUG_READY_READ"))){
				return false;
			}
			var count = retryRecord.compute(new Entry(result.getName(), Arrays.asList(result.getParameters())), (k, v) -> {
				if(v == null) v = 0;
				if(v>=maxRetryCount) return v;
				return ++v;
			});
			return count<maxRetryCount;
		}
	}
	
	@Test
	void testChunk() throws IOException{
		AllocateTicket.bytes(10).submit(DataProvider.newVerySimpleProvider());
	}
	@Test(dependsOnMethods = {"testChunk"})
	void testRef() throws IOException{
		var ch = AllocateTicket.bytes(10).submit(DataProvider.newVerySimpleProvider());
		
		var refPipe = Reference.standardPipe();
		var ref     = new Reference(ChunkPointer.of(10), 10);
		refPipe.write(ch, ref);
		
	}
	@Test(dependsOnMethods = "testRef")
	void implPipe() throws IOException{
		
		@StructPipe.Special
		interface Test extends IOInstance.Def<Test>{
			int val();
		}
		
		var ch = AllocateTicket.bytes(10).submit(DataProvider.newVerySimpleProvider());
		try(var w = ch.write(0, true)){
			w.writeInt4(69420);
		}
		
		var pipe = StandardStructPipe.of(Test.class);
		var test = pipe.readNew(ch, null);
		assertThat(test.val()).isEqualTo(69420);
	}
	
	private final Map<FieldDef, Integer> retries = new ConcurrentHashMap<>();
	
	@Test(dataProvider = "fieldVariations", retryAnalyzer = SoftRetry.class, dependsOnMethods = {"testChunk", "testRef", "implPipe"})
	<T1 extends IOInstance<T1>, T2 extends IOInstance<T2>> void testType(FieldDef field, boolean immediate) throws IOException, LockedFlagSet{
		LogUtil.println("Testing: " + field + (immediate? ", immediate" : ""));
		
		var fields = List.of(field);
		
		BasicSpecial<T1, T2> pipes = makeBasicSpecial(fields, immediate? 0 : retries.compute(field, (k, c) -> c == null? 1 : c + 1)*15);
		
		boolean debRead = getPipeFlag(pipes.specialPipe(), "DEBUG_READY_READ");
		assertThat(debRead).as("Pipe generation flag: DEBUG_READY_READ").isEqualTo(immediate);
		
		var random = new RawRandom();//field.toString().hashCode()
		for(int i = 0; i<50; i++){
			doIOTest(fields, pipes.basicPipe(), pipes.basicPipe(), random);
			doIOTest(fields, pipes.basicPipe(), pipes.specialPipe(), random);
			doIOTest(fields, pipes.specialPipe(), pipes.basicPipe(), random);
		}
	}
	
	@Test(dependsOnMethods = "testRef")
	<T1 extends IOInstance<T1>, T2 extends IOInstance<T2>> void aBunchOfFlags() throws LockedFlagSet, IOException{
		var nullable = Annotations.make(IONullability.class, Map.of("value", IONullability.Mode.NULLABLE));
		var vns      = Annotations.make(IODependency.VirtualNumSize.class);
//		var allFields = fieldPermutations().stream().toList();
//		var fields     = Iters.ofInts(181, 100, 168, 200, 197, 186, 184, 206).mapToObj(allFields::get).enumerate((i, e) -> e.withName("val" + i)).toList();
		var fields = Iters.of(
			new FieldDef(boolean.class, RandomGenerator::nextBoolean)
//			new FieldDef(Integer.class, r -> r.nextBoolean()? r.nextInt() : null).withAnnotations(List.of(vns, nullable))
		).repeat(25).enumerate((i, e) -> e.withName("val" + i)).toList();
		
		BasicSpecial<T1, T2> pipes = makeBasicSpecial(fields, 0);
		
		doIOTest(fields, pipes.basicPipe(), pipes.basicPipe(), new RawRandom(0));
		doIOTest(fields, pipes.basicPipe(), pipes.specialPipe(), new RawRandom(0));

//		for(int i = 0; i<1000; i++){
//			var random = new RawRandom(i);
//			doIOTest(fields, pipes.basicPipe(), pipes.specialPipe(), random);
//		}
	}
	
	
	@Test(dependsOnMethods = {"testType", "aBunchOfFlags"})
	<T1 extends IOInstance<T1>, T2 extends IOInstance<T2>> void testMultiFuzz(){
		var allFields = fieldPermutations().stream().toList();
		
		var fuz = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.of(
			(r, actionIndex, mark) -> {
				int fieldCount = r.nextInt(2, 30);
				var ids        = Iters.rand(r, fieldCount, 0, allFields.size()).box().bake();
				var fields     = ids.map(allFields::get).enumerate((i, e) -> e.withName("val" + i)).toList();
				
				if(mark.action(actionIndex)){
					LogUtil.println("Failed on IDS:", ids);
					LogUtil.println("Fields:\n" + Iters.from(fields).joinAsStr("\n", e -> "  " + e));
					int i = 0;
				}
				
				var random = new RawRandom(fields.toString().hashCode());
				
				BasicSpecial<T1, T2> pipes;
				try{
					pipes = makeBasicSpecial(fields, 0);
				}catch(UnsupportedOperationException e){
					return;
				}
				
				for(int i = 0; i<20; i++){
					doIOTest(fields, pipes.basicPipe(), pipes.basicPipe(), random);
					doIOTest(fields, pipes.basicPipe(), pipes.specialPipe(), random);
					doIOTest(fields, pipes.specialPipe(), pipes.basicPipe(), random);
				}
			}
		), FuzzingRunner::noopAction);
		
		FuzzingUtils.stableRun(Plan.start(fuz, 123, 10_000, 1), "testMultiFuzz");
	}
	
	@SuppressWarnings("unchecked")
	private static <T1 extends IOInstance<T1>, T2 extends IOInstance<T2>> BasicSpecial<T1, T2> makeBasicSpecial(List<FieldDef> fields, int specialDelay) throws LockedFlagSet{
		var basic   = (Class<T1>)makeFieldClass(fields, false, specialDelay);
		var special = (Class<T2>)makeFieldClass(fields, true, specialDelay);
		
		try(var ignore = ConfigDefs.DO_INTEGRITY_CHECK.temporarySet(false)){
			StructPipe<T1> basicPipe   = makeUncheckedPipe(basic, specialDelay == 0);
			StructPipe<T2> specialPipe = makeUncheckedPipe(special, specialDelay == 0);
			
			assertThat(basicPipe).isNotInstanceOf(StructPipe.SpecializedImplementation.class);
			assertThat(specialPipe).isInstanceOf(StructPipe.SpecializedImplementation.class);
			
			return new BasicSpecial<>(basicPipe, specialPipe);
		}
	}
	
	private record BasicSpecial<T1 extends IOInstance<T1>, T2 extends IOInstance<T2>>(StructPipe<T1> basicPipe, StructPipe<T2> specialPipe){ }
	
	private static <T2 extends IOInstance<T2>> boolean getPipeFlag(StructPipe<T2> specialPipe, String name){
		boolean debRead;
		try{
			var debReadF = specialPipe.getClass().getDeclaredField(name);
			debRead = (boolean)debReadF.get(null);
		}catch(Throwable e){
			throw new RuntimeException(Log.fmt("Failed to get {}#red flag", name), e);
		}
		return debRead;
	}
	private static <T1 extends IOInstance<T1>> StructPipe<T1> makeUncheckedPipe(Class<T1> basic, boolean imidiate){
		StructPipe<T1> pipe = imidiate? StandardStructPipe.of(basic, StagedInit.STATE_DONE) : StandardStructPipe.of(basic);
		if(pipe instanceof CheckedPipe.Standard<T1> ch){
			return ch.getUncheckedPipe();
		}
		return pipe;
	}
	
	@SuppressWarnings("unchecked")
	private static <TF extends IOInstance<TF>, TT extends IOInstance<TT>>
	void doIOTest(List<FieldDef> fieldsInfo, StructPipe<TF> from, StructPipe<TT> to, RandomGenerator random) throws IOException{
		TF val = from.getType().make();
		
		for(FieldDef field : fieldsInfo){
			var f1 = (IOField<TF, Object>)from.getType().getFields().byName(field.name).orElseThrow();
			f1.set(null, val, field.generator.apply(random));
		}
		
		
		var ch = AllocateTicket.bytes(10).submit(DataProvider.newVerySimpleProvider());
		from.write(ch, val);
		TT readNew = to.readNew(ch, null);
		TT doNew   = to.getType().make();
		try(var io = ch.io()){
			to.read(ch.getDataProvider(), io, doNew, null);
		}
		
		for(FieldDef field : fieldsInfo){
			
			if(field.getter){
				assert getIntField(val, field.name + "_getCount") != 0;
				assert getIntField(readNew, field.name + "_getCount") == 0;
			}
			if(field.setter){
				assert getIntField(val, field.name + "_setCount") == 1;
				assert getIntField(readNew, field.name + "_setCount") == 1;
			}
			
			var f1 = (IOField<TF, Object>)from.getType().getFields().byName(field.name).orElseThrow();
			var f2 = (IOField<TT, Object>)to.getType().getFields().byName(field.name).orElseThrow();
			
			var val1 = f1.get(null, val);
			var val2 = f2.get(null, readNew);
			var val3 = f2.get(null, doNew);
			
			assertThat(val1).isEqualTo(val2);
			assertThat(val1).isEqualTo(val3);
			assertThat(val2).isEqualTo(val3);
		}
	}
	
	private static int getIntField(Object val, String name){
		try{
			var f1 = val.getClass().getDeclaredField(name);
			f1.setAccessible(true);
			return (int)f1.get(val);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	private static Class<IOInstance<?>> makeFieldClass(List<FieldDef> fieldDefs, boolean special, int specialDelay){
		var fields = new ArrayList<TempClassGen.FieldGen>();
		var fns    = new ArrayList<UnsafeConsumer<CodeStream, MalformedJorth>>();
		for(FieldDef field : fieldDefs){
			fields.add(new TempClassGen.FieldGen(
				field.name, TempClassGen.VisiblityGen.PRIVATE, false, field.type,
				Iters.concat1N(Annotations.make(IOValue.class), field.annotations).toList(),
				field.generator
			));
			
			var upper = TextUtil.firstToUpperCase(field.name);
			
			if(field.getter){
				var getCounterName = field.name + "_getCount";
				fields.add(new TempClassGen.FieldGen(
					getCounterName, TempClassGen.VisiblityGen.PRIVATE, false, int.class,
					List.of(), null
				));
				fns.add(code -> code.write(
					"""
						@ {0}
						public function get{1}
							returns {3}
						start
							get this {4}
							inc 1
							set this {4}
							get this {2}
							return
						end
						""",
					IOValue.class, upper, field.name, field.type, getCounterName
				));
			}
			if(field.setter){
				var setCounter = field.name + "_setCount";
				fields.add(new TempClassGen.FieldGen(
					setCounter, TempClassGen.VisiblityGen.PRIVATE, false, int.class,
					List.of(), null
				));
				fns.add(code -> code.write(
					"""
						@ {0}
						public function set{1}
							arg val {3}
						start
							get this {4}
							inc 1
							set this {4}
							get #arg val
							set this {2}
						end
						""",
					IOValue.class, upper, field.name, field.type, setCounter
				));
			}
		}
		var cg = new TempClassGen.ClassGen(
			"unnamed",
			fields,
			Set.of(new TempClassGen.CtorType.Empty()),
			IOInstance.Managed.class,
			special? List.of(Annotations.make(StructPipe.Special.class, Map.of("debugStructDelay", specialDelay))) : List.of(),
			fns);
		return TempClassGen.gen(cg);
	}
}
