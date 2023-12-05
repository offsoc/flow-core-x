/*
 * Copyright 2018 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flowci.core.job.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collection;

/**
 * @author yang
 */
public interface LoggingService {

    /**
     * Save log into file system, return the id of file
     *
     * @param file file
     */
    String save(MultipartFile file);

    /**
     * Get log resource
     *
     * @param stepId step id
     * @return file resource
     */
    Resource get(String stepId);

    /**
     * Read cached log from step id
     */
    Collection<byte[]> read(String stepId);
}
