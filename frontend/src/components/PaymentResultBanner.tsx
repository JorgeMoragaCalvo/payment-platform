import { isPaymentResult, paymentResultText, PAYMENT_RESULT_TONE, type Voice } from '../lib/copy'

/**
 * The strip shown after coming back from Webpay. The same five outcomes in both views, in each
 * view's own voice and each view's own shell: a Bootstrap alert for staff, the full-width strip for
 * the customer. It sits OUTSIDE any collapsible panel on purpose — it confirms an action just taken
 * and must not need a click to be seen.
 */
export function PaymentResultBanner({ result, voice, variant }: {
  result: string | null
  voice: Voice
  variant: 'alert' | 'strip'
}) {
  if (!isPaymentResult(result)) return null

  const text = paymentResultText(voice, result)
  const tone = PAYMENT_RESULT_TONE[result]

  if (variant === 'alert') {
    return <div className={`alert alert-${tone}`} role="alert">{text}</div>
  }

  return (
    <div className={`pa-result pa-result--${tone}`} role="alert">
      <div className="pa-strip">{text}</div>
    </div>
  )
}
