<template>
    <div>
        <headerNav title="收银台"/>
        <van-cell-group>
            <van-cell title="订单商品名" :value="transaction.orderSubject" />
            <van-cell title="价格" :value="transaction.price / 100.0" />
        </van-cell-group>
        <van-button slot="button" size="small" type="primary" @click="submit(9999)">模拟支付</van-button>
    </div>
</template>

<script>
  import { getTransaction, submitTransaction } from '../../api/pay';
  import pingpp from 'pingpp-js';
  import { Dialog } from 'vant';

  export default {

    data() {
      return {
        appId: this.$route.query.appId,
        orderId: this.$route.query.orderId,
        returnUrl: this.$route.query.returnUrl,
        transaction: {},
      }
    },

    mounted() {
      let response = getTransaction(this.appId, this.orderId);
      response.then(data => {
        this.transaction = data;
      });
    },

    methods: {
      submit(payChannel) {
        let that = this;
        submitTransaction(this.appId, this.orderId, payChannel).then(data => {
          pingpp.createPayment(data.invokeResponse, function(result, err) {
            if (result === 'success') {
              Dialog.alert({
                title: '系统提示',
                message: '支付成功',
                beforeClose: function (action, done) {
                  // 关闭弹窗
                  done();
                  // 跳转到我的优惠劵
                  that.$router.push(decodeURI(that.returnUrl));
                }
              });
            } else {
              // console.log(err.msg);
              // console.log(err.extra);
              Dialog.alert({
                title: '系统提示',
                message: '支付失败：' + err.msg,
              });
            }
          });
        });
      }
    }

  }
</script>
