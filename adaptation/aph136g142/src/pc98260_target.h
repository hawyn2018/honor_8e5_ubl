/* pc98260_target.h — per-kernel selector for the 6.12 adaptation.
 *
 * Build with -DPC98260_TARGET_6138 for Magic8Pro 6.12.38;
 * default is Honor 6.12.58.
 */
#ifndef PC98260_TARGET_H
#define PC98260_TARGET_H

#ifdef PC98260_TARGET_APH136G142
#include "pc98260_target_aph136g142.h"
#elif defined(PC98260_TARGET_APH112)
#include "pc98260_target_aph112.h"
#elif defined(PC98260_TARGET_61258B)
#include "pc98260_target_61258b.h"
#elif defined(PC98260_TARGET_6138Y)
#include "pc98260_target_6138y.h"
#elif defined(PC98260_TARGET_6123)
#include "pc98260_target_6123.h"
#elif defined(PC98260_TARGET_6138)
#include "pc98260_target_6138.h"
#else
#include "pc98260_target_61258.h"
#endif

#endif /* PC98260_TARGET_H */
