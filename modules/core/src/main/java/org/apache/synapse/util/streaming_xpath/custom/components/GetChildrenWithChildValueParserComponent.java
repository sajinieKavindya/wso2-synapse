/*
 *  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.synapse.util.streaming_xpath.custom.components;

import org.apache.axiom.om.OMElement;

import javax.xml.namespace.QName;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class GetChildrenWithChildValueParserComponent extends ParserComponent {
    ParserComponent nextParserComponent;
    String localName;
    String nameSpacePrefix;
    QName childQName;
    String childValue;

    public GetChildrenWithChildValueParserComponent(String localName, String nameSpacePrefix, String value) {
        this.localName = localName;
        this.nameSpacePrefix = nameSpacePrefix;
        this.childValue = value;
    }

    @Override
    public String process(OMElement node) {
        this.childQName = new QName(prefixNameSpaceMap.get(nameSpacePrefix), localName);
        try{
            Iterator children = node.getChildElements();
            OMElement child = (OMElement) children.next();
            OMElement grandChild = child.getFirstChildWithName(childQName);
            if (grandChild != null) {
                if (childValue.equals(grandChild.getText())) {
                    if (nextParserComponent == null) {
                        return child.toString();
                    } else {
                        return nextParserComponent.process(child);
                    }
                } else {
                    return null;
                }
            } else {
                return "";
            }
        }catch (NoSuchElementException e){
            return "";
        }
    }

    @Override
    public void setNext(ParserComponent parserComponent) {
        this.nextParserComponent = parserComponent;
    }

    @Override
    public ParserComponent getNext() {
        return this.nextParserComponent;
    }
}
