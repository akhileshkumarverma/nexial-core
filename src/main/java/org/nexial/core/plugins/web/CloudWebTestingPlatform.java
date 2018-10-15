/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nexial.core.plugins.web;

import javax.validation.constraints.NotNull;

import org.nexial.core.model.ExecutionContext;
import org.openqa.selenium.WebDriver;

public abstract class CloudWebTestingPlatform {
    protected ExecutionContext context;
    protected boolean isRunningLocal;
    protected String localExeName;
    protected boolean isMobile;
    protected String browserVersion;
    protected String browserName;
    protected boolean pageSourceSupported;

    protected CloudWebTestingPlatform(ExecutionContext context) { this.context = context; }

    public boolean isRunningLocal() { return isRunningLocal; }

    public String getLocalExeName() { return localExeName;}

    public boolean isMobile() { return isMobile; }

    public boolean isPageSourceSupported() { return pageSourceSupported; }

    public String getBrowserVersion() { return browserVersion; }

    public String getBrowserName() { return browserName; }

    @NotNull
    public abstract WebDriver initWebDriver();

    protected abstract void terminateLocal();
}
