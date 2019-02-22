/**
 * Copyright © 2019, JDA Software Group, Inc. ALL RIGHTS RESERVED.
 * <p>
 * This software is the confidential information of JDA Software, Inc., and is licensed
 * as restricted rights software. The use,reproduction, or disclosure of this software
 * is subject to restrictions set forth in your license agreement with JDA.
 */

package com.jda.dct.chatservice.controller;

import com.jda.dct.chatservice.service.MattermostPassthroughService;
import java.net.URISyntaxException;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * Mattermost pass trough controller.
 */
@RestController
@RequestMapping("/chat/passthrough")
public class MattermostPassthroughController {

    private final MattermostPassthroughService service;

    public MattermostPassthroughController(@Autowired MattermostPassthroughService service) {
        Assert.notNull(service, "Proxy service can't null");
        this.service = service;
    }

    @RequestMapping("/**")
    public ResponseEntity passthrough(@RequestBody(required = false) String body,
                                      HttpMethod method,
                                      HttpServletRequest request) {
        return service.passthrough(body, method, request);
    }
}
