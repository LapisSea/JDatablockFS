package com.lapissea.dfs.run;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzSequenceSource;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.Plan;
import com.lapissea.fuzz.RunMark;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.random.RandomGenerator;

import static com.lapissea.dfs.type.StagedInit.STATE_DONE;
import static org.testng.Assert.assertEquals;

public final class StructFuzzTest{
	
	private static final List<BiFunction<String, RandomGenerator, TempClassGen.FieldGen>> fieldTypes = List.of(
		StructFuzzTest::signedInt,
		StructFuzzTest::unsignedInt,
		StructFuzzTest::string,
		StructFuzzTest::generic
	);
	
	private static final Annotation
		IOVal    = Annotations.make(IOValue.class),
		Unsigned = Annotations.make(IOValue.Unsigned.class),
		Gen      = Annotations.make(IOValue.Generic.class),
		vns      = Annotations.make(IODependency.VirtualNumSize.class),
		Nullable = Annotations.make(IONullability.class, Map.of("value", IONullability.Mode.NULLABLE));
	
	private static Annotation opt(RandomGenerator rand, Annotation ann){
		return rand.nextBoolean()? ann : null;
	}
	private static List<Annotation> anns(Annotation... anns){
		return Iters.of(anns).nonNulls().toList();
	}
	
	private static boolean isFinal(RandomGenerator rand){
		return false;
	}
	
	private static TempClassGen.FieldGen signedInt(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name, int.class, anns(IOVal, opt(rand, vns)), isFinal(rand),
			RandomGenerator::nextInt
		);
	}
	
	private static TempClassGen.FieldGen unsignedInt(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name, int.class, anns(IOVal, Unsigned, opt(rand, vns)), isFinal(rand),
			r -> r.nextInt(Integer.MAX_VALUE)
		);
	}
	
	private static TempClassGen.FieldGen string(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name, String.class, anns(IOVal, opt(rand, Nullable)), isFinal(rand),
			r -> Iters.rand(r, r.nextInt(100), 0, 255)
			          .mapToObj(i -> Character.toString((char)i)).joinAsStr()
		);
	}
	private static TempClassGen.FieldGen generic(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name, Object.class, anns(IOVal, Gen, opt(rand, Nullable)), isFinal(rand),
			r -> switch(rand.nextInt(3)){
				case 0 -> rand.nextInt();
				case 1 -> "hello world";
				case 2 -> NumberSize.SHORT;
				default -> throw new IllegalStateException("Unexpected value: " + rand.nextInt(3));
			}
		);
	}
	@Test
	void simpleGenClass() throws ReflectiveOperationException, IOException{
		testType(new TempClassGen.ClassGen(
			"testBefore",
			List.of(new TempClassGen.FieldGen("f1", int.class, anns(IOVal), false, RandomGenerator::nextInt)),
			Set.of(new TempClassGen.CtorType.All(), new TempClassGen.CtorType.Empty()),
			IOInstance.Managed.class
		));
	}
	
	@Test(dependsOnMethods = "simpleGenClass")
	public void fuzzGen(){
		var runner = new FuzzingRunner<>(new FuzzingStateEnv.Marked<TempClassGen.ClassGen, Object, IOException>(){
			@Override
			public void applyAction(TempClassGen.ClassGen state, long actionIndex, Object action, RunMark mark) throws IOException{
				try{
					testType(state);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			}
			
			@Override
			public TempClassGen.ClassGen create(RandomGenerator rand, long sequenceIndex, RunMark mark){
				var fieldCount = rand.nextInt(3);
				
				var fields = Iters.rand(rand, fieldCount, 0, fieldTypes.size()).enumerate()
				                  .toList(e -> fieldTypes.get(e.val()).apply("field" + e.index(), rand));
				
				Set<TempClassGen.CtorType> constructors = new HashSet<>();
				if(!fields.isEmpty()) constructors.add(new TempClassGen.CtorType.All());
				if(Iters.from(fields).noneMatch(TempClassGen.FieldGen::isFinal)) constructors.add(new TempClassGen.CtorType.Empty());
				
				return new TempClassGen.ClassGen(
					"test" + sequenceIndex,
					fields,
					constructors,
					IOInstance.Managed.class
				);
			}
		}, FuzzingRunner::noopAction);
		
		
		Plan.start(
			runner, new FuzzConfig().withName("fuzzChainResize"),
			new FuzzSequenceSource.LenSeed(69420, 4_000, 1)
//			() -> Stream.of(FuzzSequence.fromDataStick("REPLACE_ME"))
		).runAll().assertFail();
		
	}
	
	public <T extends IOInstance<T>> void testType(TempClassGen.ClassGen def) throws IOException, ReflectiveOperationException{
//		LogUtil.println(def);
//		LogUtil.println(def.name());
		
		var typ    = (Class<T>)TempClassGen.gen(def);
		var struct = Struct.of(typ, STATE_DONE);
		var pipe   = StandardStructPipe.of(struct, STATE_DONE);
		
		var fs   = Iters.from(def.fields());
		var rand = new RawRandom(def.name().hashCode());
		var ctor = typ.getConstructor(fs.map(TempClassGen.FieldGen::type).toArray(Class[]::new));
		
		
		var mem = Cluster.emptyMem();
		var ch  = AllocateTicket.bytes(64).submit(mem);
		
		for(int i = 0; i<50; i++){
			var inst = ctor.newInstance(fs.map(t -> t.generator().apply(rand)).toArray(Object[]::new));
			
			pipe.write(ch, inst);
			
			var read = pipe.readNew(ch, null);
			
			assertEquals(read, inst, "" + i);
		}
	}
	
}
