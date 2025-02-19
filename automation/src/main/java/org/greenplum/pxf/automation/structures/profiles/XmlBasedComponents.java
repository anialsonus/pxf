package org.greenplum.pxf.automation.structures.profiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;

/** Wraps xmlFile and give basic actions for it. */
public abstract class XmlBasedComponents {

	private String xmlFilePath;

	private XMLConfiguration conf;

	private static final long _2_SECONDS = 2000;

	XmlBasedComponents(String fileName) {
		this.xmlFilePath = fileName;
	}

	/**
	 * @return {@link XMLConfiguration} for a file
	 * @throws Exception if an error occurs
	 */
	protected XMLConfiguration getConf() throws Exception {

		if (conf == null || conf.isEmpty()) {
			conf = new XMLConfiguration(new File(xmlFilePath));
		}
		return conf;
	}

	/**
	 * Saves {@link XMLConfiguration} to its file location
	 * 
	 * @param conf - the configuration
	 * @throws ConfigurationException if an error occurs saving configuration
	 * @throws IOException if I/O error occurs
	 * @throws InterruptedException if the thread is interrupted
	 */
	protected void save(XMLConfiguration conf) throws ConfigurationException, IOException,
			InterruptedException {

		FileOutputStream fos = new FileOutputStream(conf.getFile());

		conf.save(fos);

		// Making sure the file
		fos.close();

		// wait to make sure new change loaded
		Thread.sleep(_2_SECONDS);
	}

	public String getXmlFilePath() {
		return xmlFilePath;
	}

	public void setXmlFilePath(String xmlFilePath) {
		this.xmlFilePath = xmlFilePath;
	}
}