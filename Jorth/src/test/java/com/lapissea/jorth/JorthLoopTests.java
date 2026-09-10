package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthLoopTests{
	private static ClassDefinition definition(){
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("test.LoopScaffold"));
		return cd;
	}
	
	@Test
	public void independentCallbackScopes() throws Exception{
		var outer = definition().function("run").staticAcc().arg(int.class, "count").body();
		outer.var(int.class, "i").val(0).set("i");
		var callbacks = new ArrayList<String>();
		assertThat(outer.loop(check -> {
			callbacks.add("check");
			assertThat(check.hasVar("i")).isTrue();
			assertThat(check.hasVar("count")).isTrue();
			assertThat(check.hasVar("tmp")).isFalse();
			check.var(String.class, "tmp").val("condition").set("tmp");
			check.get("i").get("count").lessThanOp();
		}, body -> {
			callbacks.add("body");
			assertThat(body.hasVar("tmp")).isFalse();
			body.var(long.class, "tmp").val(123L).set("tmp");
			body.get("i").add(1).set("i");
		})).isSameAs(outer);
		assertThat(callbacks).containsExactly("check", "body");
		assertThat(outer.hasVar("tmp")).isFalse();
		outer.var(Object.class, "tmp").nullVal(Object.class).set("tmp");
		outer.get("i").pop();
		assertThat(outer.stackView()).isEmpty();
	}
	
	@Test
	public void callbacksCannotEditParentAndAreClosedAfterBuilding() throws Exception{
		var outer = definition().function("run").staticAcc().body();
		var captured = new CodeBlock[2];
		outer.loop(check -> {
			captured[0] = check;
			assertThatThrownBy(() -> outer.val(1)).isInstanceOf(IllegalAccessError.class);
			assertThatThrownBy(() -> outer.var(int.class, "leaked")).isInstanceOf(IllegalAccessError.class);
			check.val(false);
		}, body -> {
			captured[1] = body;
			assertThatThrownBy(() -> outer.val(1)).isInstanceOf(IllegalAccessError.class);
			assertThatThrownBy(() -> outer.var(int.class, "leaked")).isInstanceOf(IllegalAccessError.class);
			assertThatThrownBy(() -> captured[0].val(true)).isInstanceOf(IllegalAccessError.class);
		});
		for(var block : captured){
			assertThatThrownBy(() -> block.val(1)).isInstanceOf(IllegalAccessError.class);
			assertThatThrownBy(() -> block.var(int.class, "late")).isInstanceOf(IllegalAccessError.class);
		}
		outer.val(1).pop();
	}
	
	@Test
	public void callbackFailureUnfreezesParent() throws Exception{
		for(boolean failCheck : new boolean[]{true, false}){
			var outer = definition().function("run").staticAcc().body();
			assertThatThrownBy(() -> outer.loop(check -> {
				if(failCheck) throw new MalformedJorth("callback failure");
				check.val(true);
			}, body -> {
				throw new MalformedJorth("callback failure");
			})).isInstanceOf(MalformedJorth.class).hasMessage("callback failure");
			outer.val(1).pop();
		}
	}
	
	private static void appendTrace(CodeBlock code, int marker) throws MalformedJorth{
		code.get("trace").call("add", args -> args.val(marker).box()).pop();
	}
	
	@Test(timeOut = 10000)
	public void edgeInvocationTrace() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			var code = cd.function("run").staticAcc().arg(int.class, "count").arg(List.class, "trace").body();
			appendTrace(code, 10);
			code.var(int.class, "i").val(0).set("i")
			    .loop(check -> {
				    appendTrace(check, 20);
				    check.get("i").get("count").lessThanOp();
			    }, body -> {
				    body.get("trace").call("add", args -> args.get("i").add(30).box()).pop();
				    body.get("i").add(1).set("i");
			    });
			appendTrace(code, 40);
		});
		var method = cls.getMethod("run", int.class, List.class);
		var trace = new ArrayList<Integer>();
		method.invoke(null, 3, trace);
		assertThat(trace).containsExactly(10, 20, 30, 20, 31, 20, 32, 20, 40);
		trace.clear();
		method.invoke(null, 0, trace);
		assertThat(trace).containsExactly(10, 20, 40);
		trace.clear();
		method.invoke(null, 1, trace);
		assertThat(trace).containsExactly(10, 20, 30, 20, 40);
	}

	@Test(timeOut = 10000)
	public void accumulatesSum() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("sum").staticAcc().arg(int.class, "count").returns(int.class).body()
			  .var(int.class, "i").val(0).set("i")
			  .var(int.class, "sum").val(0).set("sum")
			  .loop(check -> check.get("i").get("count").lessThanOp(), body -> {
				  body.call(Integer.class, "sum", args -> args.get("sum").get("i")).set("sum");
				  body.get("i").add(1).set("i");
			  })
			  .get("sum").returnOp();
		});
		var sum = cls.getMethod("sum", int.class);
		for(int count : new int[]{-1, 0, 1, 2, 10, 100}){
			assertThat(sum.invoke(null, count)).isEqualTo(count<0? 0 : count*(count - 1)/2);
		}
	}

	@Test(timeOut = 10000)
	public void scopedLocalsExecuteWithDifferentTypes() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc().arg(int.class, "count").arg(List.class, "trace").body()
			  .var(int.class, "i").val(0).set("i")
			  .loop(check -> {
				  check.var(int.class, "tmp").get("i").set("tmp");
				  check.get("trace").call("add", args -> args.get("tmp").box()).pop();
				  check.get("tmp").get("count").lessThanOp();
			  }, body -> {
				  body.var(long.class, "tmp").get("i").cast(long.class).add(10).set("tmp");
				  body.get("trace").call("add", args -> args.get("tmp").box()).pop();
				  body.get("i").add(1).set("i");
			  })
			  .var(String.class, "tmp").val("done").set("tmp")
			  .get("trace").call("add", args -> args.get("tmp")).pop();
		});
		var run = cls.getMethod("run", int.class, List.class);
		var trace = new ArrayList<Object>();
		run.invoke(null, 3, trace);
		assertThat(trace).containsExactly(0, 10L, 1, 11L, 2, 12L, 3, "done");
		trace.clear();
		run.invoke(null, 0, trace);
		assertThat(trace).containsExactly(0, "done");
	}
}
