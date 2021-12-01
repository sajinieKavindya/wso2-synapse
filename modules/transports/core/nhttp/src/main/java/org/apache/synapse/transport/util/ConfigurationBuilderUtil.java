package org.apache.synapse.transport.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Properties;

public class ConfigurationBuilderUtil {

    private static final Log LOGGER = LogFactory.getLog(ConfigurationBuilderUtil.class);

    /**
     * Get an int property that tunes pass-through http transport. Prefer system properties
     *
     * @param name name of the system/config property
     * @param def  default value to return if the property is not set
     * @return the value of the property to be used
     */
    public static Integer getIntProperty(String name, Integer def, Properties props) {
        String val = System.getProperty(name);
        if (val == null) {
            val = props.getProperty(name);
        }

        if (val != null) {
            int intVal;
            try {
                intVal = Integer.valueOf(val);
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid pass-through http tuning property value. " + name +
                        " must be an integer");
                return def;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Using pass-through http tuning parameter : " + name + " = " + val);
            }
            return intVal;
        }

        return def;
    }

    /**
     * Return true if user has configured an int property that tunes pass-through http transport.
     *
     * @param name  name of the system/config property
     * @return      true if property is configured
     */
    public static boolean isIntPropertyConfigured(String name, Properties props) {

        String val = System.getProperty(name);
        if (val == null) {
            val = props.getProperty(name);
        }

        if (val != null) {
            try {
                Integer.parseInt(val);
            } catch (NumberFormatException e) {
                LOGGER.warn("Incorrect pass-through http tuning property value. " + name +
                        " must be an integer");
                return false;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Using configured pass-through http tuning property value for : " + name);
            }
            return true;
        }

        return false;
    }

    /**
     * Get an int property that tunes pass-through http transport. Prefer system properties
     *
     * @param name name of the system/config property
     * @return the value of the property, null if the property is not found
     */
    public static Integer getIntProperty(String name, Properties props) {
        return getIntProperty(name, null, props);
    }

    /**
     * Get a boolean property that tunes pass-through http transport. Prefer system properties
     *
     * @param name name of the system/config property
     * @param def  default value to return if the property is not set
     * @return the value of the property to be used
     */
    public static Boolean getBooleanProperty(String name, Boolean def, Properties props) {
        String val = System.getProperty(name);
        if (val == null) {
            val = props.getProperty(name);
        }

        if (val != null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Using pass-through http tuning parameter : " + name + " = " + val);
            }
            return Boolean.valueOf(val);
        }

        return def;
    }

    /**
     * Get a Boolean property that tunes pass-through http transport. Prefer system properties
     *
     * @param name name of the system/config property
     * @return the value of the property, null if the property is not found
     */
    public static Boolean getBooleanProperty(String name, Properties props) {
        return getBooleanProperty(name, null, props);
    }

    /**
     * Get a String property that tunes pass-through http transport. Prefer system properties
     *
     * @param name name of the system/config property
     * @param def  default value to return if the property is not set
     * @return the value of the property to be used
     */
    public static String getStringProperty(String name, String def, Properties props) {
        String val = System.getProperty(name);
        if (val == null) {
            val = props.getProperty(name);
        }

        return val == null ? def : val;
    }

}
