package com.flowci.core.test.trigger;

import com.flowci.core.test.SpringScenario;
import com.flowci.core.trigger.dao.TriggerDeliveryDao;
import com.flowci.core.trigger.domain.TriggerDelivery;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testng.Assert;

import java.util.Date;

public class TriggerDeliveryDaoTest extends SpringScenario {

    @Autowired
    private TriggerDeliveryDao triggerDeliveryDao;

    private TriggerDelivery delivery;

    @Before
    public void init() {
        delivery = new TriggerDelivery();
        delivery.setTriggerId("1123");
        triggerDeliveryDao.save(delivery);
    }

    @Test
    public void should_add_delivery_and_load_items() {
        createItems(10);

        var optional = triggerDeliveryDao.findById(delivery.getId());
        Assert.assertFalse(optional.isEmpty());

        var loaded = optional.get();
        Assert.assertEquals(10, loaded.getDeliveries().size());

        Page<TriggerDelivery.Item> items = triggerDeliveryDao.listDeliveries(delivery.getTriggerId(), PageRequest.of(1, 5));
        Assert.assertNotNull(items);
    }

    private void createItems(int total) {
        for (int i = 0; i < total; i++) {
            var item = new TriggerDelivery.Item();
            item.setTimestamp(new Date());
            item.setStatus("success");
            triggerDeliveryDao.addDelivery(delivery.getTriggerId(), item);
        }
    }
}
