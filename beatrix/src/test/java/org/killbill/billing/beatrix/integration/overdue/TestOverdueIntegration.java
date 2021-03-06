/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014 Groupon, Inc
 * Copyright 2014 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.beatrix.integration.overdue;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.beatrix.integration.BeatrixIntegrationModule;
import org.killbill.billing.beatrix.util.InvoiceChecker.ExpectedInvoiceItemCheck;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.catalog.api.PriceListSet;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.api.BlockingApiException;
import org.killbill.billing.entitlement.api.DefaultEntitlement;
import org.killbill.billing.entitlement.api.Entitlement;
import org.killbill.billing.entitlement.api.EntitlementApiException;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.api.InvoicePayment;
import org.killbill.billing.invoice.model.ExternalChargeInvoiceItem;
import org.killbill.billing.junction.DefaultBlockingState;
import org.killbill.billing.payment.api.Payment;
import org.killbill.billing.payment.api.PluginProperty;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

// For all the tests, we set the the property org.killbill.payment.retry.days=8,8,8,8,8,8,8,8 so that Payment retry logic does not end with an ABORTED state
// preventing final instant payment to succeed.
//
// The tests are difficult to follow because there are actually two tracks of retry in logic:
// - The payment retries
// - The overdue notifications
//

@Test(groups = "slow")
public class TestOverdueIntegration extends TestOverdueBase {

    private final static Integer TIME_SINCE_EARLIEST_INVOICE_TO_TRIGGER_BLOCKING_BILLING = 40;

    @Override
    public String getOverdueConfig() {
        final String configXml = "<overdueConfig>" +
                                 "   <accountOverdueStates>" +
                                 "       <initialReevaluationInterval>" +
                                 "           <unit>DAYS</unit><number>5</number>" +
                                 "       </initialReevaluationInterval>" +
                                 "       <state name=\"OD3\">" +
                                 "           <condition>" +
                                 "               <timeSinceEarliestUnpaidInvoiceEqualsOrExceeds>" +
                                 "                   <unit>DAYS</unit><number>50</number>" +
                                 "               </timeSinceEarliestUnpaidInvoiceEqualsOrExceeds>" +
                                 "           </condition>" +
                                 "           <externalMessage>Reached OD3</externalMessage>" +
                                 "           <blockChanges>true</blockChanges>" +
                                 "           <disableEntitlementAndChangesBlocked>true</disableEntitlementAndChangesBlocked>" +
                                 "           <autoReevaluationInterval>" +
                                 "               <unit>DAYS</unit><number>5</number>" +
                                 "           </autoReevaluationInterval>" +
                                 "       </state>" +
                                 "       <state name=\"OD2\">" +
                                 "           <condition>" +
                                 "               <timeSinceEarliestUnpaidInvoiceEqualsOrExceeds>" +
                                 "                   <unit>DAYS</unit><number>" + TIME_SINCE_EARLIEST_INVOICE_TO_TRIGGER_BLOCKING_BILLING + "</number>" +
                                 "               </timeSinceEarliestUnpaidInvoiceEqualsOrExceeds>" +
                                 "           </condition>" +
                                 "           <externalMessage>Reached OD2</externalMessage>" +
                                 "           <blockChanges>true</blockChanges>" +
                                 "           <disableEntitlementAndChangesBlocked>true</disableEntitlementAndChangesBlocked>" +
                                 "           <autoReevaluationInterval>" +
                                 "               <unit>DAYS</unit><number>5</number>" +
                                 "           </autoReevaluationInterval>" +
                                 "       </state>" +
                                 "       <state name=\"OD1\">" +
                                 "           <condition>" +
                                 "               <timeSinceEarliestUnpaidInvoiceEqualsOrExceeds>" +
                                 "                   <unit>DAYS</unit><number>30</number>" +
                                 "               </timeSinceEarliestUnpaidInvoiceEqualsOrExceeds>" +
                                 "           </condition>" +
                                 "           <externalMessage>Reached OD1</externalMessage>" +
                                 "           <blockChanges>true</blockChanges>" +
                                 "           <disableEntitlementAndChangesBlocked>false</disableEntitlementAndChangesBlocked>" +
                                 "           <autoReevaluationInterval>" +
                                 "               <unit>DAYS</unit><number>5</number>" +
                                 "           </autoReevaluationInterval>" +
                                 "       </state>" +
                                 "   </accountOverdueStates>" +
                                 "</overdueConfig>";

        return configXml;
    }

    @Test(groups = "slow", description = "Test overdue stages and return to clear prior to CTD", enabled=false)
    public void testOverdueStages1() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Set next invoice to fail and create subscription
        paymentPlugin.makeAllInvoicesFailWithError(true);
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // 2012, 5, 31 => DAY 30 have to get out of trial {I0, P0}
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // 2012, 6, 8 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 16 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 24 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 31 => P1 (We se 6/31 instead of 6/30 because invoice might happen later in that day)
        addDaysAndCheckForCompletion(7, NextEvent.BLOCK, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);
        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // 2012, 7, 2 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");

        // 2012, 7, 9 => Retry P1
        addDaysAndCheckForCompletion(7, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");

        // 2012, 7, 10 => Retry P0
        //
        // This is the first stage that will block the billing (and entitlement).
        //
        addDaysAndCheckForCompletion(1, NextEvent.BLOCK, NextEvent.TAG, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 17 => Retry P1
        addDaysAndCheckForCompletion(7, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 18 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 23 => Should be 20 but notficationQ event occurs on 23...
        addDaysAndCheckForCompletion(5, NextEvent.BLOCK);
        checkODState("OD3");

        allowPaymentsAndResetOverdueToClearByPayingAllUnpaidInvoices(false);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 10), new LocalDate(2012, 7, 23), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-104.82")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 23), new LocalDate(2012, 7, 23), InvoiceItemType.CBA_ADJ, new BigDecimal("104.82")));

        // Add 10 days to generate next invoice. We verify that we indeed have a notification for nextBillingDate
        addDaysAndCheckForCompletion(10, NextEvent.INVOICE, NextEvent.PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 4, callContext,
                                    // Item for the upgraded recurring plan
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2012, 8, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 8, 2), new LocalDate(2012, 8, 2), InvoiceItemType.CBA_ADJ, new BigDecimal("-104.82")));

        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 8, 31), callContext);

        // Verify the account balance is now 0
        assertEquals(invoiceUserApi.getAccountBalance(account.getId(), callContext).compareTo(BigDecimal.ZERO), 0);
    }

    @Test(groups = "slow", description = "Test overdue stages and return to clear on CTD", enabled=false)
    public void testOverdueStages2() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Set next invoice to fail and create subscription
        paymentPlugin.makeAllInvoicesFailWithError(true);
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // 2012, 5, 31 => DAY 30 have to get out of trial {I0, P0}
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // 2012, 6, 8 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 16 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 24 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 31 => P1 (We se 6/31 instead of 6/30 because invoice might happen later in that day)
        addDaysAndCheckForCompletion(7, NextEvent.BLOCK, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);
        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // 2012, 7, 2 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");

        // 2012, 7, 9 => Retry P1
        addDaysAndCheckForCompletion(7, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");

        // 2012, 7, 10 => Retry P0
        //
        // This is the first stage that will block the billing (and entitlement).
        //
        addDaysAndCheckForCompletion(1, NextEvent.BLOCK, NextEvent.TAG, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 17 => Retry P1
        addDaysAndCheckForCompletion(7, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 18 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 23 => Should be 20 but notficationQ event occurs on 23...
        addDaysAndCheckForCompletion(5, NextEvent.BLOCK);
        checkODState("OD3");

        // 2012, 7, 25 => Retry P0
        addDaysAndCheckForCompletion(2, NextEvent.PAYMENT_ERROR);
        // 2012, 7, 26 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);

        // 2012, 7, 31 => No NEW INVOICE because OD2 -> still blocked
        addDaysAndCheckForCompletion(5);
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        allowPaymentsAndResetOverdueToClearByPayingAllUnpaidInvoices(true);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    // New invoice for the partial period since we unblocked on the 1st and so are missing the 31 july
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 10), new LocalDate(2012, 7, 31), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-169.32")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2012, 7, 31), InvoiceItemType.CBA_ADJ, new BigDecimal("169.32")));

        invoiceChecker.checkInvoice(account.getId(), 4, callContext,
                                    // New invoice for the partial period since we unblocked on the 1st and so are missing the 31 july
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2012, 8, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2012, 7, 31), InvoiceItemType.CBA_ADJ, new BigDecimal("-169.32")));

        // Move one month ahead, and check if we get the next invoice
        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 5, callContext,
                                    // New invoice for the partial period since we unblocked on the 1st and so are missing the 31 july
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 8, 31), new LocalDate(2012, 9, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));

        // Verify the account balance is now 0
        assertEquals(invoiceUserApi.getAccountBalance(account.getId(), callContext).compareTo(BigDecimal.ZERO), 0);
    }

    @Test(groups = "slow", description = "Test overdue stages and return to clear after CTD", enabled=false)
    public void testOverdueStages3() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Set next invoice to fail and create subscription
        paymentPlugin.makeAllInvoicesFailWithError(true);
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // 2012, 5, 31 => DAY 30 have to get out of trial {I0, P0}
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // 2012, 6, 8 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 16 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 24 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 31 => P1 (We se 6/31 instead of 6/30 because invoice might happen later in that day)
        addDaysAndCheckForCompletion(7, NextEvent.BLOCK, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);
        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // 2012, 7, 2 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");

        // 2012, 7, 9 => Retry P1
        addDaysAndCheckForCompletion(7, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");

        // 2012, 7, 10 => Retry P0
        //
        // This is the first stage that will block the billing (and entitlement).
        //
        addDaysAndCheckForCompletion(1, NextEvent.BLOCK, NextEvent.TAG, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 17 => Retry P1
        addDaysAndCheckForCompletion(7, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 18 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 23 => Should be 20 but notficationQ event occurs on 23...
        addDaysAndCheckForCompletion(5, NextEvent.BLOCK);
        checkODState("OD3");

        // 2012, 7, 25 => Retry P0
        addDaysAndCheckForCompletion(2, NextEvent.PAYMENT_ERROR);
        // 2012, 7, 26 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);

        // 2012, 7, 31 => No NEW INVOICE because OD2 -> still blocked
        addDaysAndCheckForCompletion(5);
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // 2012, 8, 1 => Nothing should have happened
        addDaysAndCheckForCompletion(1);

        allowPaymentsAndResetOverdueToClearByPayingAllUnpaidInvoices(true);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    // New invoice for the partial period since we unblocked on the 1st and so are missing the 31 july
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 10), new LocalDate(2012, 7, 31), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-169.32")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 8, 1), new LocalDate(2012, 8, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("169.32")));

        invoiceChecker.checkInvoice(account.getId(), 4, callContext,
                                    // New invoice for the partial period since we unblocked on the 1st and so are missing the 31 july
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 8, 1), new LocalDate(2012, 8, 31), InvoiceItemType.RECURRING, new BigDecimal("241.89")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 8, 1), new LocalDate(2012, 8, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("-169.32")));

        // Move one month ahead, and check if we get the next invoice
        addDaysAndCheckForCompletion(30, NextEvent.INVOICE, NextEvent.PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 5, callContext,
                                    // New invoice for the partial period since we unblocked on the 1st and so are missing the 31 july
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 8, 31), new LocalDate(2012, 9, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));

        // Verify the account balance is now 0
        assertEquals(invoiceUserApi.getAccountBalance(account.getId(), callContext).compareTo(BigDecimal.ZERO), 0);
    }

    //
    // This test is similar to the previous one except that instead of moving the clock to check we will get the next invoice
    // at the end, we carry a change of plan.
    //
    @Test(groups = "slow", description = "Test overdue stages and follow with an immediate change of plan", enabled=false)
    public void testOverdueStagesFollowedWithImmediateChange1() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Set next invoice to fail and create subscription
        paymentPlugin.makeAllInvoicesFailWithError(true);
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // 2012, 5, 31 => DAY 30 have to get out of trial {I0, P0}
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // 2012, 6, 8 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 16 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 24 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 31 => P1 (We se 6/31 instead of 6/30 because invoice might happen later in that day)
        addDaysAndCheckForCompletion(7, NextEvent.BLOCK, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);
        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // 2012, 7, 2 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");

        // 2012, 7, 9 => Retry P1
        addDaysAndCheckForCompletion(7, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");

        // 2012, 7, 10 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.BLOCK, NextEvent.TAG, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 17 => Retry P1
        addDaysAndCheckForCompletion(7, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 18 => Retry P0
        addDaysAndCheckForCompletion(1, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 23 => Should be 20 but notficationQ event occurs on 23...
        addDaysAndCheckForCompletion(5, NextEvent.BLOCK);
        checkODState("OD3");

        allowPaymentsAndResetOverdueToClearByPayingAllUnpaidInvoices(false);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 10), new LocalDate(2012, 7, 23), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-104.82")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 23), new LocalDate(2012, 7, 23), InvoiceItemType.CBA_ADJ, new BigDecimal("104.82")));

        // Do an upgrade now
        checkChangePlanWithOverdueState(baseEntitlement, false, false);

        invoiceChecker.checkRepairedInvoice(account.getId(), 3, callContext,
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 10), new LocalDate(2012, 7, 23), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-104.82")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 23), new LocalDate(2012, 7, 23), InvoiceItemType.CBA_ADJ, new BigDecimal("104.82")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 23), new LocalDate(2012, 7, 31), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-64.50")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 23), new LocalDate(2012, 7, 23), InvoiceItemType.CBA_ADJ, new BigDecimal("64.50")));

        invoiceChecker.checkInvoice(account.getId(), 4, callContext,
                                    // Item for the upgraded recurring plan
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 23), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("154.83")),
                                    // Repair for upgrade
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 23), new LocalDate(2012, 7, 23), InvoiceItemType.CBA_ADJ, new BigDecimal("-154.83")));

        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // Verify the account balance:
        assertEquals(invoiceUserApi.getAccountBalance(account.getId(), callContext).compareTo(new BigDecimal("-14.49")), 0);
    }

    @Test(groups = "slow", description = "Test overdue stages and follow with an immediate change of plan and use of credit", enabled = false)
    public void testOverdueStagesFollowedWithImmediateChange2() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Set next invoice to fail and create subscription
        paymentPlugin.makeAllInvoicesFailWithError(true);
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, BillingPeriod.ANNUAL, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // 2012, 5, 31 => DAY 30 have to get out of trial {I0, P0}
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2013, 5, 31), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2013, 5, 31), callContext);

        // 2012, 6, 8 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 16 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 6, 24 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // 2012, 7, 2 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.BLOCK, NextEvent.PAYMENT_ERROR);
        checkODState("OD1");

        // 2012, 7, 10 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.BLOCK, NextEvent.PAYMENT_ERROR, NextEvent.TAG);
        checkODState("OD2");

        // 2012, 7, 18 => Retry P0
        addDaysAndCheckForCompletion(8, NextEvent.PAYMENT_ERROR);
        checkODState("OD2");

        // 2012, 7, 23 => Should be 20 but notficationQ event occurs on 23...
        addDaysAndCheckForCompletion(5, NextEvent.BLOCK);
        checkODState("OD3");

        allowPaymentsAndResetOverdueToClearByPayingAllUnpaidInvoices(false);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    // New invoice for the part that was unblocked up to the BCD
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2013, 5, 31), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 10), new LocalDate(2012, 7, 23), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-85.4588")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2013, 5, 31), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-1998.9012")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 23), new LocalDate(2012, 7, 23), InvoiceItemType.CBA_ADJ, new BigDecimal("2084.36")));

        // Move to 2012, 7, 31 and Make a change of plan
        addDaysAndCheckForCompletion(8, NextEvent.INVOICE, NextEvent.PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    // New invoice for the part that was unblocked up to the BCD
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2013, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));

        checkChangePlanWithOverdueState(baseEntitlement, false, false);

        invoiceChecker.checkRepairedInvoice(account.getId(), 3, callContext,
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2013, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2013, 7, 31), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2399.95")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2012, 7, 31), InvoiceItemType.CBA_ADJ, new BigDecimal("2399.95")));

        invoiceChecker.checkInvoice(account.getId(), 4, callContext,
                                    // Item for the upgraded recurring plan
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2012, 8, 31), InvoiceItemType.RECURRING, new BigDecimal("599.95")),
                                    // Credits consumed
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2012, 7, 31), InvoiceItemType.CBA_ADJ, new BigDecimal("-599.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 8, 31), callContext);
        assertEquals(invoiceUserApi.getAccountBalance(account.getId(), callContext).compareTo(new BigDecimal("-1800")), 0);
    }

    @Test(groups = "slow", description = "Test overdue stages with missing payment method", enabled=false)
    public void testOverdueStateIfNoPaymentMethod() throws Exception {
        // This test is similar to the previous one - but there is no default payment method on the account, so there
        // won't be any payment retry

        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Make sure the account doesn't have any payment method
        accountInternalApi.removePaymentMethod(account.getId(), internalCallContext);

        // Create subscription
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // DAY 30 have to get out of trial before first payment. A payment error, one for each invoice, should be on the bus (because there is no payment method)
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 45 - 15 days after invoice
        addDaysAndCheckForCompletion(15);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 65 - 35 days after invoice
        // Single PAYMENT_ERROR here here triggered by the invoice
        addDaysAndCheckForCompletion(20, NextEvent.BLOCK, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // Now we should be in OD1
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);

        // DAY 67 - 37 days after invoice
        addDaysAndCheckForCompletion(2);

        // Should still be in OD1
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);

        // DAY 75 - 45 days after invoice
        addDaysAndCheckForCompletion(8, NextEvent.BLOCK, NextEvent.TAG);

        // Should now be in OD2
        checkODState("OD2");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);

        // DAY 85 - 55 days after invoice
        addDaysAndCheckForCompletion(10, NextEvent.BLOCK);

        // Should now be in OD3
        checkODState("OD3");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);

        // Add a payment method and set it as default
        paymentApi.addPaymentMethod(account, UUID.randomUUID().toString(), BeatrixIntegrationModule.NON_OSGI_PLUGIN_NAME, true, paymentMethodPlugin, PLUGIN_PROPERTIES, callContext);

        allowPaymentsAndResetOverdueToClearByPayingAllUnpaidInvoices(false);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    // Item for the upgraded recurring plan
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 15), new LocalDate(2012, 7, 25), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-80.63")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 25), new LocalDate(2012, 7, 25), InvoiceItemType.CBA_ADJ, new BigDecimal("80.63")));

        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        checkChangePlanWithOverdueState(baseEntitlement, false, false);

        invoiceChecker.checkRepairedInvoice(account.getId(), 3, callContext,
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 15), new LocalDate(2012, 7, 25), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-80.63")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 25), new LocalDate(2012, 7, 25), InvoiceItemType.CBA_ADJ, new BigDecimal("80.63")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 25), new LocalDate(2012, 7, 31), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-48.38")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 25), new LocalDate(2012, 7, 25), InvoiceItemType.CBA_ADJ, new BigDecimal("48.38")));

        invoiceChecker.checkInvoice(account.getId(), 4, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 25), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("116.12")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 25), new LocalDate(2012, 7, 25), InvoiceItemType.CBA_ADJ, new BigDecimal("-116.12")));

        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        assertEquals(invoiceUserApi.getAccountBalance(account.getId(), callContext).compareTo(new BigDecimal("-12.89")), 0);
    }

    @Test(groups = "slow", description = "Test overdue from non paid external charge", enabled=false)
    public void testShouldBeInOverdueAfterExternalCharge() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Create a subscription without failing payments
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // Create an external charge on a new invoice
        addDaysAndCheckForCompletion(5);
        busHandler.pushExpectedEvents(NextEvent.INVOICE_ADJUSTMENT);
        final InvoiceItem externalCharge = new ExternalChargeInvoiceItem(null, account.getId(), bundle.getId(), "For overdue", new LocalDate(2012, 5, 6), BigDecimal.TEN, Currency.USD);
        invoiceUserApi.insertExternalCharges(account.getId(), clock.getUTCToday(), ImmutableList.<InvoiceItem>of(externalCharge), callContext).get(0);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 6), null, InvoiceItemType.EXTERNAL_CHARGE, BigDecimal.TEN));

        // DAY 30 have to get out of trial before first payment
        addDaysAndCheckForCompletion(25, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // Should still be in clear state - the invoice for the bundle has been paid, but not the invoice with the external charge
        // We refresh overdue just to be safe, see below
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // Past 30 days since the external charge
        addDaysAndCheckForCompletion(6, NextEvent.BLOCK);
        // Note! We need to explicitly refresh here because overdue won't get notified to refresh up until the next
        // payment (when the next invoice is generated)
        // TODO - we should fix this
        // We should now be in OD1
        checkODState("OD1");

        // Pay the invoice
        final Invoice externalChargeInvoice = invoiceUserApi.getUnpaidInvoicesByAccountId(account.getId(), clock.getUTCToday(), callContext).iterator().next();
        createExternalPaymentAndCheckForCompletion(account, externalChargeInvoice, NextEvent.PAYMENT, NextEvent.BLOCK);
        // We should be clear now
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);
    }

    @Test(groups = "slow", description = "Test overdue after refund with no adjustment", enabled=false)
    public void testShouldBeInOverdueAfterRefundWithoutAdjustment() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Create subscription and don't fail payments
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // DAY 30 have to get out of trial before first payment
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 45 - 15 days after invoice
        addDaysAndCheckForCompletion(15);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 65 - 35 days after invoice
        addDaysAndCheckForCompletion(20, NextEvent.INVOICE, NextEvent.PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // Now, refund the second (first non-zero dollar) invoice
        final Payment payment = paymentApi.getPayment(invoiceUserApi.getInvoicesByAccount(account.getId(), callContext).get(1).getPayments().get(0).getPaymentId(), false, PLUGIN_PROPERTIES, callContext);
        refundPaymentAndCheckForCompletion(account, payment, NextEvent.BLOCK, NextEvent.INVOICE_ADJUSTMENT);
        // We should now be in OD1
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);
    }

    @Test(groups = "slow", description = "Test overdue after chargeback", enabled=false)
    public void testShouldBeInOverdueAfterChargeback() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Create subscription and don't fail payments
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // DAY 30 have to get out of trial before first payment
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 45 - 15 days after invoice
        addDaysAndCheckForCompletion(15);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 65 - 35 days after invoice
        addDaysAndCheckForCompletion(20, NextEvent.INVOICE, NextEvent.PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // Now, create a chargeback for the second (first non-zero dollar) invoice
        final InvoicePayment invoicePayment = invoicePaymentApi.getInvoicePayments(invoiceUserApi.getInvoicesByAccount(account.getId(), callContext).get(1).getPayments().get(0).getPaymentId(), callContext).get(0);
        final Payment payment = paymentApi.getPayment(invoicePayment.getPaymentId(), false, ImmutableList.<PluginProperty>of(), callContext);
        createChargeBackAndCheckForCompletion(account, payment, NextEvent.BLOCK, NextEvent.INVOICE_ADJUSTMENT);
        // We should now be in OD1
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);
    }

    @Test(groups = "slow", description = "Test overdue clear after external payment", enabled=false)
    public void testOverdueStateShouldClearAfterExternalPayment() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Set next invoice to fail and create subscription
        paymentPlugin.makeAllInvoicesFailWithError(true);
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // DAY 30 have to get out of trial before first payment
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 45 - 15 days after invoice
        addDaysAndCheckForCompletion(15, NextEvent.PAYMENT_ERROR);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 65 - 35 days after invoice
        addDaysAndCheckForCompletion(20, NextEvent.BLOCK, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // Now we should be in OD1
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);

        // We have two unpaid non-zero dollar invoices at this point
        // Pay the first one via an external payment - we should then be 5 days apart from the second invoice
        // (which is the earliest unpaid one) and hence come back to a clear state (see configuration)
        final Invoice firstNonZeroInvoice = invoiceUserApi.getUnpaidInvoicesByAccountId(account.getId(), clock.getUTCToday(), callContext).iterator().next();
        createExternalPaymentAndCheckForCompletion(account, firstNonZeroInvoice, NextEvent.PAYMENT, NextEvent.BLOCK);
        // We should be clear now
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);
    }

    @Test(groups = "slow", description = "Test overdue clear after item adjustment", enabled=false)
    public void testOverdueStateShouldClearAfterCreditOrInvoiceItemAdjustment() throws Exception {
        clock.setTime(new DateTime(2012, 5, 1, 0, 3, 42, 0));

        // Set next invoice to fail and create subscription
        paymentPlugin.makeAllInvoicesFailWithError(true);
        final DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        bundle = subscriptionApi.getSubscriptionBundle(baseEntitlement.getBundleId(), callContext);

        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 5, 1), callContext);

        // DAY 30 have to get out of trial before first payment
        addDaysAndCheckForCompletion(30, NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 6, 30), callContext);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 45 - 15 days after invoice
        addDaysAndCheckForCompletion(15, NextEvent.PAYMENT_ERROR);

        // Should still be in clear state
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 65 - 35 days after invoice
        addDaysAndCheckForCompletion(20, NextEvent.BLOCK, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR, NextEvent.PAYMENT_ERROR);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // Now we should be in OD1
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);

        // We have two unpaid non-zero dollar invoices at this point
        // Adjust the first (and only) item of the first invoice - we should then be 5 days apart from the second invoice
        // (which is the earliest unpaid one) and hence come back to a clear state (see configuration)
        final Invoice firstNonZeroInvoice = invoiceUserApi.getUnpaidInvoicesByAccountId(account.getId(), clock.getUTCToday(), callContext).iterator().next();
        fullyAdjustInvoiceItemAndCheckForCompletion(account, firstNonZeroInvoice, 1, NextEvent.BLOCK, NextEvent.INVOICE_ADJUSTMENT);
        // We should be clear now
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        invoiceChecker.checkRepairedInvoice(account.getId(), 2,
                                            callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                            new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 5, 31), InvoiceItemType.ITEM_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkChargedThroughDate(baseEntitlement.getId(), new LocalDate(2012, 7, 31), callContext);

        // DAY 70 - 10 days after second invoice
        addDaysAndCheckForCompletion(5);

        // We should still be clear
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 80 - 20 days after second invoice
        addDaysAndCheckForCompletion(10, NextEvent.PAYMENT_ERROR);

        // We should still be clear
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);

        // DAY 95 - 35 days after second invoice
        addDaysAndCheckForCompletion(15, NextEvent.BLOCK, NextEvent.PAYMENT_ERROR, NextEvent.INVOICE, NextEvent.PAYMENT_ERROR);

        // We should now be in OD1
        checkODState("OD1");
        checkChangePlanWithOverdueState(baseEntitlement, true, true);

        invoiceChecker.checkInvoice(account.getId(), 4, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 31), new LocalDate(2012, 8, 31), InvoiceItemType.RECURRING, new BigDecimal("249.95")));

        // Fully adjust all invoices
        final List<Invoice> invoicesToAdjust = getUnpaidInvoicesOrderFromRecent();
        for (int i = 0; i < invoicesToAdjust.size(); i++) {
            if (i == invoicesToAdjust.size() - 1) {
                fullyAdjustInvoiceAndCheckForCompletion(account, invoicesToAdjust.get(i), NextEvent.BLOCK, NextEvent.INVOICE_ADJUSTMENT);
            } else {
                fullyAdjustInvoiceAndCheckForCompletion(account, invoicesToAdjust.get(i), NextEvent.INVOICE_ADJUSTMENT);
            }
        }

        // We should be cleared again
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);
    }

    @Test(groups = "slow", enabled = false)
    public void testOverdueStateAndWRITTEN_OFFTag() throws Exception {
        // TODO add/remove tag to invoice
    }

    private void allowPaymentsAndResetOverdueToClearByPayingAllUnpaidInvoices(boolean extraInvoice) {

        // Reset plugin so payments should now succeed
        paymentPlugin.makeAllInvoicesFailWithError(false);

        //
        // We now pay all unpaid invoices.
        //
        // Upon paying the last invoice, the overdue system will clear the state and notify invoice that it should re-generate a new invoice
        // for the part hat was unblocked, which explains why on the last payment we expect an additional invoice and payment.
        //
        final List<Invoice> sortedInvoices = getUnpaidInvoicesOrderFromRecent();

        int remainingUnpaidInvoices = sortedInvoices.size();
        for (final Invoice invoice : sortedInvoices) {
            if (invoice.getBalance().compareTo(BigDecimal.ZERO) > 0) {
                remainingUnpaidInvoices--;
                if (remainingUnpaidInvoices > 0) {
                    createPaymentAndCheckForCompletion(account, invoice, NextEvent.PAYMENT);
                } else {
                    if (extraInvoice) {
                        createPaymentAndCheckForCompletion(account, invoice, NextEvent.BLOCK, NextEvent.TAG, NextEvent.INVOICE_ADJUSTMENT, NextEvent.PAYMENT, NextEvent.INVOICE, NextEvent.PAYMENT);
                    } else {
                        createPaymentAndCheckForCompletion(account, invoice, NextEvent.BLOCK, NextEvent.TAG, NextEvent.INVOICE_ADJUSTMENT, NextEvent.PAYMENT);
                    }
                }
            }
        }
        checkODState(DefaultBlockingState.CLEAR_STATE_NAME);
    }

    private List<Invoice> getUnpaidInvoicesOrderFromRecent() {
        final Collection<Invoice> invoices = invoiceUserApi.getUnpaidInvoicesByAccountId(account.getId(), clock.getUTCToday(), callContext);
        // Sort in reverse order to first pay most recent invoice-- that way overdue state may only flip when we reach the last one.
        final List<Invoice> sortedInvoices = new LinkedList<Invoice>(invoices);
        Collections.sort(sortedInvoices, new Comparator<Invoice>() {
            @Override
            public int compare(final Invoice i1, final Invoice i2) {
                return i2.getInvoiceDate().compareTo(i1.getInvoiceDate());
            }
        });
        return sortedInvoices;
    }

    private void checkChangePlanWithOverdueState(final Entitlement entitlement, final boolean shouldFail, final boolean expectedPayment) {
        if (shouldFail) {
            try {
                entitlement.changePlan("Pistol", term, PriceListSet.DEFAULT_PRICELIST_NAME, callContext);
            } catch (EntitlementApiException e) {
                assertTrue(e.getCause() instanceof BlockingApiException || e.getCode() == ErrorCode.SUB_CHANGE_NON_ACTIVE.getCode(),
                           String.format("Cause is %s, message is %s", e.getCause(), e.getMessage()));
            }
        } else {
            // Upgrade - we don't expect a payment here due to the scenario (the account will have some CBA)
            if (expectedPayment) {
                changeEntitlementAndCheckForCompletion(entitlement, "Assault-Rifle", BillingPeriod.MONTHLY, null, NextEvent.CHANGE, NextEvent.INVOICE_ADJUSTMENT, NextEvent.INVOICE, NextEvent.PAYMENT);
            } else {
                changeEntitlementAndCheckForCompletion(entitlement, "Assault-Rifle", BillingPeriod.MONTHLY, null, NextEvent.CHANGE, NextEvent.INVOICE_ADJUSTMENT, NextEvent.INVOICE);
            }
        }
    }
}
