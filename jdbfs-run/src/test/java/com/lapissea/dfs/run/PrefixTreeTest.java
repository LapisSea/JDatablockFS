package com.lapissea.dfs.run;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.collections.IOSet;
import com.lapissea.dfs.objects.collections.PrefixTree;
import com.lapissea.dfs.run.checked.CheckSet;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.type.Struct;
import com.lapissea.fuzz.FailOrder;
import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzSequenceSource;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.Plan;
import com.lapissea.fuzz.RNGEnum;
import com.lapissea.fuzz.RunMark;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

import static com.lapissea.dfs.run.FuzzingUtils.stableRun;
import static com.lapissea.dfs.run.TestUtils.optionallyLogged;

public class PrefixTreeTest{
	
	@BeforeClass
	void before(){
		try{
			Struct.ofUnknown(PrefixTree.class, Struct.STATE_DONE);
		}catch(Throwable e){
			throw new RuntimeException("Failed to compile PrefixTree", e);
		}
	}
	
	private final LateInit<DataLogger, RuntimeException> LOGGER = LoggedMemoryUtils.createLoggerFromConfig();
	
	private Cluster testCluster;
	private String  testName;
	@BeforeMethod
	void setupCluster(Method method){
		testName = method.getName();
	}
	
	@AfterMethod
	void tearDownCluster(Method method) throws IOException{
		if(testCluster == null) return;
		var s = testCluster.getSource();
		s.write(false, s.read(0, 1));
		var ses = LOGGER.get().getSession(method.getName());
		ses.finish();
	}
	
	private synchronized CheckSet<String> makeChecked() throws IOException{
		if(testCluster == null){
			var mem = LoggedMemoryUtils.newLoggedMemory(testName, LOGGER);
			testCluster = Cluster.init(mem);
		}
		return makeChecked(testCluster);
	}
	private static CheckSet<String> makeChecked(Cluster cluster) throws IOException{
		var data = cluster.roots().request(0, PrefixTree.class);
		return new CheckSet<>(data);
	}
	
	@Test
	void simpleAdd() throws IOException{
		var checked = makeChecked();
		checked.add("AA-1");
		checked.add("AA-2");
		checked.add("AA-3");
		checked.add("AB-1");
		checked.add("");
		checked.add(null);
	}
	
	@Test(dependsOnMethods = "simpleAdd")
	void simpleRemove() throws IOException{
		var checked = makeChecked();
		checked.add("Dummy");
		checked.remove("Test");
		checked.add("Test");
		checked.remove("Test");
	}
	
	@Test(dependsOnMethods = {"simpleAdd", "simpleRemove"})
	void simpleContains() throws IOException{
		var checked = makeChecked();
		
		checked.add("Hello world");
		checked.add("Hey mum");
		checked.add("Hello there");
		
		checked.contains("Hi...?");
		checked.contains("Hello there");
		checked.remove("Hello there");
		checked.contains("Hello there");
	}
	
	@Test(dependsOnMethods = "simpleAdd")
	void simpleClear() throws IOException{
		var checked = makeChecked();
		
		checked.add("Hello world");
		checked.add("Hey mum");
		
		checked.clear();
	}
	
	@Test
	void fuzz(){
		record State(Cluster cluster, IOSet<String> set) implements Serializable{
			
			record StateForm(byte[] data) implements Serializable{
				@SuppressWarnings({"rawtypes", "unchecked"})
				@Serial
				private Object readResolve() throws IOException{
					var cluster = new Cluster(MemoryData.of(data));
					return new State(cluster, new CheckSet<>(cluster.roots().require(1, IOSet.class)));
				}
			}
			
			@Serial
			private Object writeReplace() throws IOException{
				return new StateForm(State.this.cluster.getSource().readAll());
			}
			
			@Override
			public boolean equals(Object obj){
				try{
					return obj instanceof State that &&
					       Arrays.equals(this.cluster.getSource().readAll(), that.cluster.getSource().readAll());
				}catch(IOException e){
					throw new UncheckedIOException(e);
				}
			}
		}
		
		enum Type{ADD, REMOVE, CONTAINS, CLEAR}
		record Action(Type type, String val) implements Serializable{
			@Override
			public String toString(){ return type == Type.CLEAR? type.toString() : type + "-" + val; }
		}
		
		var runner = new FuzzingRunner<State, Action, IOException>(
			new FuzzingStateEnv.Marked<>(){
				@Override
				public State create(RandomGenerator random, long sequenceIndex, RunMark mark) throws IOException{
					var cluster = optionallyLogged(mark.sequence(sequenceIndex), "set-fuzz" + sequenceIndex);
					return new State(cluster, makeChecked(cluster));
				}
				@Override
				public void applyAction(State state, long actionIndex, Action action, RunMark mark) throws IOException{
					if(mark.hasSequence() && action.val.isEmpty()){
						LogUtil.println("checked." + action.type.toString().toLowerCase() + "(\"" + action.val + "\");");
					}
					if(mark.action(actionIndex)){
						int a = 0;
					}
					switch(action.type){
						case ADD -> state.set.add(action.val);
						case REMOVE -> state.set.remove(action.val);
						case CONTAINS -> state.set.contains(action.val);
						case CLEAR -> state.set.clear();
					}
//					if(List.of(Type.REMOVE, Type.CLEAR, Type.ADD).contains(action.type)){
//						state.cluster.scanGarbage(ERROR);
//						state.cluster.getChunkCache().validate(state.cluster);
//					}
				}
			},
			RNGEnum.of(Type.class)
			       .chanceFor(Type.CLEAR, 1F/1000)
			       .map((e, rand) -> new Action(e, rand.ints(rand.nextInt(50), 'a', 'a' + 4).mapToObj(n -> ((char)n) + "").collect(Collectors.joining())))
		);
		stableRun(
			Plan.start(runner, new FuzzConfig().withFailOrder(FailOrder.LEAST_ACTION).withMaxWorkers(10)
			                                   .withErrorDelay(Duration.ofSeconds(20)).withMaxErrorsTracked(100000)
				,
				       new FuzzSequenceSource.LenSeed(69, 2000_000, 2000)
//                       () -> Stream.of(new FuzzSequence(1, 1, 12312, 10_000))
			),
			"runPrefixTree"
		);
	}
	
	
}
