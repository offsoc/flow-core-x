package com.flowci.core.notification.service;

import com.flowci.core.agent.event.AgentStatusEvent;
import com.flowci.core.common.domain.Mongoable;
import com.flowci.core.common.domain.Variables;
import com.flowci.core.common.manager.ConditionManager;
import com.flowci.core.common.manager.SessionManager;
import com.flowci.core.common.manager.SpringEventManager;
import com.flowci.core.config.domain.Config;
import com.flowci.core.config.domain.SmtpConfig;
import com.flowci.core.config.service.ConfigService;
import com.flowci.core.flow.service.FlowService;
import com.flowci.core.job.event.JobFinishedEvent;
import com.flowci.core.notification.dao.NotificationDao;
import com.flowci.core.notification.domain.EmailNotification;
import com.flowci.core.notification.domain.Notification;
import com.flowci.core.notification.domain.WebhookNotification;
import com.flowci.core.notification.event.EmailTemplateParsedEvent;
import com.flowci.domain.Vars;
import com.flowci.exception.NotFoundException;
import com.flowci.exception.StatusException;
import com.flowci.util.StringHelper;
import groovy.util.ScriptException;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

@Log4j2
@Service
public class NotificationServiceImpl implements NotificationService {

    private final TemplateEngine templateEngine;

    private final NotificationDao notificationDao;

    private final ConditionManager conditionManager;

    private final SessionManager sessionManager;

    private final SpringEventManager eventManager;

    private final ThreadPoolTaskExecutor appTaskExecutor;

    private final ConfigService configService;

    private final FlowService flowService;

    private final String emailTemplate;

    public NotificationServiceImpl(TemplateEngine templateEngine,
                                   NotificationDao notificationDao,
                                   ConditionManager conditionManager,
                                   SessionManager sessionManager,
                                   SpringEventManager eventManager,
                                   ThreadPoolTaskExecutor appTaskExecutor,
                                   ConfigService configService,
                                   FlowService flowService) throws IOException {
        this.templateEngine = templateEngine;
        this.templateEngine.setTemplateResolver(new StringTemplateResolver());

        this.notificationDao = notificationDao;
        this.conditionManager = conditionManager;
        this.sessionManager = sessionManager;
        this.eventManager = eventManager;
        this.appTaskExecutor = appTaskExecutor;
        this.configService = configService;
        this.flowService = flowService;

        Resource resource = new ClassPathResource("templates/email.html");
        this.emailTemplate = new String(Files.readAllBytes(resource.getFile().toPath()));
    }

    @Override
    public List<Notification> list() {
        return notificationDao.findAll(Mongoable.SortByCreatedAtASC);
    }

    @Override
    public Notification get(String id) {
        Optional<Notification> optional = notificationDao.findById(id);
        if (optional.isPresent()) {
            return optional.get();
        }
        throw new NotFoundException("Notification {0} is not found", id);
    }

    @Override
    public Notification getByName(String name) {
        Optional<Notification> optional = notificationDao.findByName(name);
        if (optional.isPresent()) {
            return optional.get();
        }
        throw new NotFoundException("Notification {0} is not found", name);
    }

    @Override
    public void save(EmailNotification e) {
        Config config = configService.get(e.getSmtpConfig());
        if (config.getCategory() != Config.Category.SMTP) {
            throw new StatusException("SMTP config is required");
        }

        validateCondition(e);
        e.setCreatedAndUpdatedBy(sessionManager.getUserEmail());
        notificationDao.save(e);
    }

    @Override
    public void save(WebhookNotification w) {
        notificationDao.save(w);
    }

    @Override
    public void delete(String id) {
        notificationDao.deleteById(id);
    }

    @Override
    public void send(Notification n, Vars<String> context) {
        if (n.hasCondition()) {
            try {
                conditionManager.run(n.getCondition(), context);
            } catch (ScriptException e) {
                log.warn("Cannot execute condition of notification {}", n.getName());
                return;
            }
        }

        if (n instanceof EmailNotification) {
            String error = StringHelper.EMPTY;
            try {
                doSend((EmailNotification) n, context);
            } catch (Exception e) {
                log.warn("Unable to send email", e);
                error = e.getMessage();
            } finally {
                n.setError(error);
                notificationDao.save(n);
            }
            return;
        }

        if (n instanceof WebhookNotification) {
            doSend((WebhookNotification) n, context);
        }
    }

    @EventListener
    public void onJobStatusChange(JobFinishedEvent event) {
        Vars<String> context = event.getJob().getContext();
        List<Notification> list = notificationDao.findAllByTrigger(Notification.TriggerAction.OnJobFinished);
        for (Notification n : list) {
            appTaskExecutor.execute(() -> send(n, context));
        }
    }

    @EventListener
    public void onAgentStatusChange(AgentStatusEvent event) {
        Vars<String> context = event.getAgent().toContext();
        List<Notification> list = notificationDao.findAllByTrigger(Notification.TriggerAction.OnAgentStatusChange);
        for (Notification n : list) {
            appTaskExecutor.execute(() -> send(n, context));
        }
    }

    private void validateCondition(Notification n) {
        if (!n.hasCondition()) {
            return;
        }

        try {
            conditionManager.verify(n.getCondition());
        } catch (ScriptException ex) {
            throw new StatusException("Invalid groovy condition: " + ex.getMessage());
        }
    }

    private void doSend(EmailNotification n, Vars<String> context) throws MessagingException {
        JavaMailSender sender = configService.getEmailSender(n.getSmtpConfig());
        String htmlContent = templateEngine.process(emailTemplate, toThymeleafContext(context));
        eventManager.publish(new EmailTemplateParsedEvent(this, htmlContent));

        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false);

        String from = n.getFrom();
        String[] to = {n.getTo()};

        if (!n.hasFrom()) {
            SmtpConfig config = (SmtpConfig) configService.get(n.getSmtpConfig());
            from = config.getAuth().getUsername();
        }

        // load all users from flow
        if (n.isToFlowUsers()) {
            String flow = context.get(Variables.Flow.Name);
            if (!StringHelper.hasValue(flow)) {
                throw new StatusException("flow name is missing from context");
            }

            List<String> users = flowService.listUsers(flow);
            to = users.toArray(new String[0]);
        }

        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(n.getSubject());
        helper.setText(htmlContent, true);

        sender.send(mime);
        log.debug("Email notification {} has been sent", n.getName());
    }

    private void doSend(WebhookNotification n, Vars<String> context) {
    }

    private IContext toThymeleafContext(Vars<String> c) {
        Context context = new Context();
        c.forEach(context::setVariable);
        return context;
    }
}
