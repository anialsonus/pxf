package org.greenplum.pxf.automation.utils.junit;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.NumberFormat;
import java.util.Hashtable;

import junit.framework.AssertionFailedError;
import junit.framework.Test;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitResultFormatter;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitTest;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitVersionHelper;
import org.apache.tools.ant.util.FileUtils;

/**
 * JUnit Ant Formatter for PXF regression run.
 */
public class OneLinerFormatter implements JUnitResultFormatter {

	/**
	 * Where to write the log to.
	 */
	private OutputStream out;

	/**
	 * Used for writing the results.
	 */
	private PrintWriter output;

	/**
	 * Used as part of formatting the results.
	 */
	private final StringWriter results;

	/**
	 * Used for writing formatted results to.
	 */
	private final PrintWriter resultWriter;

	/**
	 * Formatter for timings.
	 */
	private final NumberFormat numberFormat = NumberFormat.getInstance();

	/**
	 * Output suite is written to System.out
	 */
	private String systemOutput = null;

	/**
	 * Output suite is written to System.err
	 */
	private String systemError = null;

	/**
	 * tests that failed.
	 */
	private final Hashtable<Test, Throwable> failedTests = new Hashtable<>();
	/**
	 * Timing helper.
	 */
	private final Hashtable<Test, Long> testStarts = new Hashtable<>();

	/**
	 * Constructor for OneLinerFormatter.
	 */
	public OneLinerFormatter() {
		results = new StringWriter();
		resultWriter = new PrintWriter(results);
	}

	@Override
	public void addError(Test test, Throwable error) {

		if (test != null) {
			if (!failedTests.containsKey(test)) {
				failedTests.put(test, error);
			}
		}
	}

	@Override
	public void addFailure(Test test, AssertionFailedError error) {

		if (test != null) {
			if (!failedTests.containsKey(test)) {
				failedTests.put(test, error);
			}
		}
	}

	@Override
	public void endTest(Test test) {


		// If for some reason the (Failed in setUp before the test get the chance to run) test is
		// not in the testStarts list, put it in.
		if (!testStarts.containsKey(test)) {
			startTest(test);
		}

		boolean failed = failedTests.containsKey(test);

		Long l = testStarts.get(test);

		output.write("Ran [");
		output.write(((System.currentTimeMillis() - l) / 1000.0) + "] ");
		output.write(getTestName(test) + " ... " + (failed ? "FAILED" : "OK"));
		output.write(System.lineSeparator());
		output.flush();
	}

	@Override
	public void startTest(Test test) {
		testStarts.put(test, System.currentTimeMillis());
	}

	@Override
	public void endTestSuite(JUnitTest suite) throws BuildException {
		StringBuilder sb = new StringBuilder("Tests run: ");
		sb.append(suite.runCount());
		sb.append(", Failures: ");
		sb.append(suite.failureCount());
		sb.append(", Errors: ");
		sb.append(suite.errorCount());
		sb.append(", Time elapsed: ");
		sb.append(numberFormat.format(suite.getRunTime() / 1000.0));
		sb.append(" sec");
		sb.append(" (").append(numberFormat.format(suite.getRunTime() / 1000.0 / 60));
		sb.append(" min)");
		sb.append(System.lineSeparator());
		sb.append(System.lineSeparator());

		if (output != null) {
			try {
				output.write(sb.toString());
				resultWriter.close();
				output.write(results.toString());
				output.flush();
			} finally {
				if (out != System.out && out != System.err) {
					FileUtils.close(out);
				}
			}
		}
	}

	@Override
	public void setOutput(OutputStream out) {
		this.out = out;
		output = new PrintWriter(out);
	}

	@Override
	public void setSystemError(String err) {
		systemError = err;
	}

	@Override
	public void setSystemOutput(String out) {
		systemOutput = out;
	}

	@Override
	public void startTestSuite(JUnitTest suite) throws BuildException {
		if (output == null) {
			return;
		}
		StringBuilder sb = new StringBuilder(System.lineSeparator());
		sb.append("----------------------------------------------------------");
		sb.append(System.lineSeparator());
		sb.append("Testsuite: ");
		sb.append(suite.getName());
		sb.append(System.lineSeparator());
		sb.append("----------------------------------------------------------");
		sb.append(System.lineSeparator());
		output.write(sb.toString());
		output.flush();
	}

	/**
	 * Get test name
	 *
	 * @param test a test
	 * @return test name
	 */
	protected String getTestName(Test test) {
		if (test == null) {
			return "null";
		} else {
			return JUnitVersionHelper.getTestCaseName(test);
		}
	}
}