package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthLoopControlFlowTests{
	private static void trace(CodeBlock code, int marker) throws MalformedJorth{
		code.get("trace").call("add", args -> args.val(marker).box()).pop();
	}
	
	@Test(timeOut = 10000)
	public void nestedLoopsWithBranchesInConditionAndBody() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc().arg(int.class, "count").arg(List.class, "trace").body()
			  .var(int.class, "i").val(0).set("i")
			  .loop(check -> {
				  trace(check, 10);
				  check.get("count").val(0).greaterThanOp()
				       .ifTrue(yes -> yes.get("i").get("count").lessThanOp())
				       .elseRun(no -> no.val(false));
			  }, outer -> {
				  trace(outer, 20);
				  outer.var(int.class, "j").val(0).set("j")
				       .loop(check -> {
					       trace(check, 30);
					       check.get("j").val(2).lessThanOp();
				       }, inner -> {
					       inner.get("j").val(0).ifEquality(zero -> trace(zero, 40)).elseRun(one -> trace(one, 41));
					       inner.get("j").add(1).set("j");
				       });
				  trace(outer, 50);
				  outer.get("i").add(1).set("i");
			  });
		});
		var run    = cls.getMethod("run", int.class, List.class);
		var events = new ArrayList<Integer>();
		run.invoke(null, 2, events);
		assertThat(events).containsExactly(10, 20, 30, 40, 30, 41, 30, 50, 10, 20, 30, 40, 30, 41, 30, 50, 10);
		events.clear();
		run.invoke(null, 0, events);
		assertThat(events).containsExactly(10);
	}
	
	@Test(timeOut = 10000)
	public void conditionalExitsSkipBackEdgeAndPostLoop() throws Exception{
		for(boolean exitInCheck : new boolean[]{false, true}){
			var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
				CodeArg exits = b -> b.get("i").val(1).ifEquality(atOne -> {
					atOne.get("mode").val(1).ifEquality(ret -> {
						trace(ret, 70);
						ret.val(123L).val(7).returnOp(); // Extra stack entries do not constrain an exiting path.
					});
					atOne.get("mode").val(2).ifEquality(thr -> {
						trace(thr, 80);
						thr.val(1.25D).newObj(IllegalStateException.class).throwOp();
					});
				});
				var code = cd.function("run").staticAcc().arg(int.class, "mode").arg(List.class, "trace").returns(int.class).body();
				trace(code, 10);
				code.var(int.class, "i").val(0).set("i").loop(check -> {
					trace(check, 20);
					if(exitInCheck) check.scope(exits);
					check.get("i").val(3).lessThanOp();
				}, body -> {
					trace(body, 30);
					if(!exitInCheck) body.scope(exits);
					trace(body, 40);
					body.get("i").add(1).set("i");
				});
				trace(code, 50);
				code.get("i").returnOp();
			});
			var run    = cls.getMethod("run", int.class, List.class);
			var events = new ArrayList<Integer>();
			assertThat(run.invoke(null, 0, events)).isEqualTo(3);
			assertThat(events).containsExactly(10, 20, 30, 40, 20, 30, 40, 20, 30, 40, 20, 50);
			events.clear();
			assertThat(run.invoke(null, 1, events)).isEqualTo(7);
			assertThat(events).isEqualTo(exitInCheck? List.of(10, 20, 30, 40, 20, 70) : List.of(10, 20, 30, 40, 20, 30, 70));
			events.clear();
			assertThatThrownBy(() -> run.invoke(null, 2, events)).hasCauseInstanceOf(IllegalStateException.class);
			assertThat(events).isEqualTo(exitInCheck? List.of(10, 20, 30, 40, 20, 80) : List.of(10, 20, 30, 40, 20, 30, 80));
		}
	}
	
	@Test(timeOut = 10000)
	public void entirelyTerminatingBodyStillAllowsZeroIterations() throws Exception{
		for(boolean throwInstead : new boolean[]{false, true}){
			var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
				var code = cd.function("run").staticAcc().arg(boolean.class, "enter").arg(List.class, "trace").returns(int.class).body();
				code.loop(check -> {
					trace(check, 10);
					check.get("enter");
				}, body -> {
					trace(body, 20);
					if(throwInstead) body.newObj(IllegalStateException.class).throwOp();
					else body.val(7).returnOp();
				});
				trace(code, 30);
				code.val(9).returnOp();
			});
			var run    = cls.getMethod("run", boolean.class, List.class);
			var events = new ArrayList<Integer>();
			assertThat(run.invoke(null, false, events)).isEqualTo(9);
			assertThat(events).containsExactly(10, 30);
			events.clear();
			if(throwInstead) assertThatThrownBy(() -> run.invoke(null, true, events)).hasCauseInstanceOf(IllegalStateException.class);
			else assertThat(run.invoke(null, true, events)).isEqualTo(7);
			assertThat(events).containsExactly(10, 20);
		}
	}
}
